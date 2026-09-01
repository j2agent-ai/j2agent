package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.*;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 按库维护：仅增量同步，不提供分库重建；全库重建由管理员全局入口执行。 */
@Slf4j
@Service
public class RepositoryMaintenanceService {
    private final KnowledgeRepoSyncService sync;
    private final KnowledgeRepoMetadataService metadata;
    private final KnowledgeRepoMaintenanceLockService locks;
    private final KnowledgeRepositoryMapper mapper;
    private final JdbcTemplate jdbc;
    private final RedissonClient redis;
    private final RedisKeyNamespaces keys;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "repository-maintenance"); t.setDaemon(true); return t;
    });
    private final ConcurrentHashMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public RepositoryMaintenanceService(KnowledgeRepoSyncService sync, KnowledgeRepoMetadataService metadata,
            KnowledgeRepoMaintenanceLockService locks, KnowledgeRepositoryMapper mapper, JdbcTemplate jdbc,
            RedissonClient redis, RedisKeyNamespaces keys) {
        this.sync=sync; this.metadata=metadata; this.locks=locks; this.mapper=mapper; this.jdbc=jdbc; this.redis=redis; this.keys=keys;
    }
    public <T> T exclusiveRepository(String id, Supplier<T> action) {
        RLock gate = locks.readLock(locks.repoRootHash(metadata.getRepoRootPath()));
        RLock repo = redis.getLock(keys.key("knowledge-repo:repository:"+id));
        boolean g=false, r=false;
        try {
            g=gate.tryLock(5, TimeUnit.SECONDS);
            if (g) r=repo.tryLock(5, TimeUnit.SECONDS);
            if (!r) throw new ResponseStatusException(HttpStatus.CONFLICT, "KNOWLEDGE_BUSY");
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Maintenance interrupted", e);
        } finally {
            if(r && repo.isHeldByCurrentThread()) repo.unlock();
            if(g && gate.isHeldByCurrentThread()) gate.unlock();
        }
    }

    /**
     * 提交按库增量同步任务。
     */
    public KnowledgeRepositoryDtos.SyncResponse submit(KnowledgeRepositoryPo po, String userId, Runnable before) {
        return exclusiveRepository(po.getId(), () -> {
            String taskId=UUIDv7Utils.randomUUIDv7(); long now=System.currentTimeMillis();
            int claimed=jdbc.update("UPDATE knowledge_repository SET status=?,updated_at=? WHERE id=? AND status NOT IN ('REBUILDING','SYNCING','DELETING')",
                    "SYNCING",now,po.getId());
            if(claimed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"KNOWLEDGE_BUSY");
            jdbc.update("INSERT INTO knowledge_repository_task(id,repository_id,repo_code,user_id,operation,status,created_at,updated_at) VALUES (?,?,?,?,?,'QUEUED',?,?)",
                    taskId,po.getId(),po.getRepoCode(),userId,"SYNC",now,now);
            AtomicReference<Future<?>> running = new AtomicReference<>();
            Future<?> future = executor.submit(() -> {
                try {
                    exclusiveRepository(po.getId(), () -> {
                        jdbc.update("UPDATE knowledge_repository_task SET status='RUNNING',updated_at=? WHERE id=?",System.currentTimeMillis(),taskId);
                        before.run();
                        // A remote pull may update repository metadata/status. Restore maintenance visibility.
                        mapper.updateStatus(po.getId(),"SYNCING",null,System.currentTimeMillis());
                        sync.executeRepositorySync(po.getRepoCode(),() -> !Thread.currentThread().isInterrupted());
                        mapper.updateStatus(po.getId(),"IDLE",null,System.currentTimeMillis());
                        jdbc.update("UPDATE knowledge_repository_task SET status='COMPLETED',updated_at=? WHERE id=?",System.currentTimeMillis(),taskId);
                        return null;
                    });
                } catch(Exception e) {
                    if (Thread.currentThread().isInterrupted() || e instanceof CancellationException
                            || e.getCause() instanceof CancellationException) {
                        logInterrupted(po.getRepoCode(), taskId, e);
                        return;
                    }
                    mapper.updateStatus(po.getId(),"FAILED",e.getMessage(),System.currentTimeMillis());
                    jdbc.update("UPDATE knowledge_repository_task SET status='FAILED',error_message=?,updated_at=? WHERE id=?",e.getMessage(),System.currentTimeMillis(),taskId);
                } finally {
                    runningTasks.remove(po.getId(), running.get());
                }
            });
            running.set(future);
            runningTasks.put(po.getId(), future);
            if (future.isDone()) {
                runningTasks.remove(po.getId(), future);
            }
            KnowledgeRepositoryDtos.SyncResponse response=new KnowledgeRepositoryDtos.SyncResponse();
            response.setSuccess(true); response.setMessage("已提交按库维护任务"); response.setTaskId(taskId); return response;
        });
    }

    /**
     * 打断该库正在执行的 Git/入库同步并等待退出。
     */
    public void interruptRunning(String id) {
        Future<?> future = runningTasks.get(id);
        if (future == null) {
            return;
        }
        future.cancel(true);
        try {
            future.get(60, TimeUnit.SECONDS);
        } catch (CancellationException | ExecutionException ignored) {
            // 任务已因中断退出
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Maintenance interrupted", e);
        } catch (TimeoutException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KNOWLEDGE_BUSY");
        }
    }

    /** 库表停在维护态但已无人执行时，判定为遗留状态的最小静默时长 */
    private static final long STALE_TASK_MILLIS = 120_000L;
    private static final Set<String> BUSY_STATUS = Set.of("SYNCING", "REBUILDING", "DELETING");

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasksOnStartup() {
        recoverInterruptedTasks();
    }

    @Scheduled(initialDelay = STALE_TASK_MILLIS, fixedDelay = STALE_TASK_MILLIS)
    public void recoverInterruptedTasksPeriodically() {
        recoverInterruptedTasks();
    }

    /**
     * 回收被进程退出打断的按库任务：维护态与 QUEUED/RUNNING 任务不会自愈，会让该库后续同步一直 409 KNOWLEDGE_BUSY。
     */
    public void recoverInterruptedTasks() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                    SELECT r.id AS id, r.repo_code AS repo_code, r.status AS status,
                           GREATEST(r.updated_at, COALESCE(MAX(t.updated_at), 0)) AS last_active
                    FROM knowledge_repository r
                    LEFT JOIN knowledge_repository_task t
                           ON t.repository_id = r.id AND t.status IN ('QUEUED', 'RUNNING')
                    WHERE r.status IN ('SYNCING', 'REBUILDING', 'DELETING') OR t.id IS NOT NULL
                    GROUP BY r.id, r.repo_code, r.status, r.updated_at
                    """);
        } catch (RuntimeException e) {
            log.warn("扫描遗留按库任务失败", e);
            return;
        }
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("id"));
            String repoCode = String.valueOf(row.get("repo_code"));
            String status = String.valueOf(row.get("status"));
            Object lastActive = row.get("last_active");
            long idleMillis = now - (lastActive instanceof Number n ? n.longValue() : 0L);
            if (idleMillis < STALE_TASK_MILLIS) {
                continue;
            }
            Future<?> local = runningTasks.get(id);
            if (local != null && !local.isDone()) {
                continue;
            }
            // 仓库锁在任务全程持有，锁还在说明其他实例仍在执行
            if (redis.getLock(keys.key("knowledge-repo:repository:" + id)).isLocked()) {
                continue;
            }
            int failedTasks = jdbc.update(
                    "UPDATE knowledge_repository_task SET status='FAILED',error_message=?,updated_at=? WHERE repository_id=? AND status IN ('QUEUED','RUNNING')",
                    "任务已被服务重启中断", now, id);
            if (BUSY_STATUS.contains(status)) {
                mapper.updateStatus(id, "IDLE", "上次维护被服务重启中断，状态已重置", now);
            }
            if (failedTasks > 0 || BUSY_STATUS.contains(status)) {
                log.warn("回收遗留按库任务: repoCode={}, status={}, 任务数={}", repoCode, status, failedTasks);
            }
        }
    }

    private void logInterrupted(String repoCode, String taskId, Exception e) {
        jdbc.update("UPDATE knowledge_repository_task SET status='FAILED',error_message=?,updated_at=? WHERE id=?",
                "任务已中断", System.currentTimeMillis(), taskId);
        log.info("按库维护被中断: repoCode={}, taskId={}", repoCode, taskId, e);
    }

    public void cleanup(KnowledgeRepositoryPo po) { sync.deleteRepositoryData(po.getRepoCode()); }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
