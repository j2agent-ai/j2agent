package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.config.rag.VectorDatabaseInit;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.config.provider.ActiveProviderHolder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 知识库维护统一协调器：单实例串行 + Redis 分布式锁，编排初始化、增量同步、完全重建。
 */
@Slf4j
@Service
@DependsOn("flywayInitializer")
public class KnowledgeRepoMaintenanceCoordinator {

    private static final Duration LOCK_WAIT_SHORT = Duration.ofSeconds(5);
    private static final Duration LOCK_WAIT_REBUILD = Duration.ofMinutes(2);
    private static final Duration STOP_PREVIOUS_TIMEOUT = Duration.ofMinutes(2);
    private static final int STARTUP_PROBE_MAX_ATTEMPTS = 50;
    private static final Duration STARTUP_PROBE_RETRY_INTERVAL = Duration.ofSeconds(5);

    private final KnowledgeRepoProperties properties;
    private final KnowledgeRepoMetadataService metadataService;
    private final KnowledgeRepoSyncService syncService;
    private final KnowledgeRepoMaintenanceLockService lockService;
    private final VectorDatabaseInit vectorDatabaseInit;
    private final EmbeddingService embeddingService;
    private final ActiveProviderHolder activeProviderHolder;
    private final KnowledgeRepoSyncProgressTracker progressTracker;
    private final ThreadPoolExecutor maintenanceExecutor;
    private final Object taskLock = new Object();
    private volatile Future<?> currentTaskFuture;
    private final AtomicLong exclusiveGeneration = new AtomicLong(0);
    private final AtomicBoolean exclusiveGate = new AtomicBoolean(false);
    private final AtomicBoolean fullRebuildRunning = new AtomicBoolean(false);
    private volatile KnowledgeRepoMaintenanceTaskType currentTaskType = KnowledgeRepoMaintenanceTaskType.IDLE;
    private volatile String lastFailureMessage;

    public KnowledgeRepoMaintenanceCoordinator(KnowledgeRepoProperties properties,
                                               KnowledgeRepoMetadataService metadataService,
                                               KnowledgeRepoSyncService syncService,
                                               KnowledgeRepoMaintenanceLockService lockService,
                                               VectorDatabaseInit vectorDatabaseInit,
                                               EmbeddingService embeddingService,
                                               ActiveProviderHolder activeProviderHolder,
                                               KnowledgeRepoSyncProgressTracker progressTracker) {
        this.properties = properties;
        this.metadataService = metadataService;
        this.syncService = syncService;
        this.lockService = lockService;
        this.vectorDatabaseInit = vectorDatabaseInit;
        this.embeddingService = embeddingService;
        this.activeProviderHolder = activeProviderHolder;
        this.progressTracker = progressTracker;
        this.maintenanceExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "knowledge-repo-maintenance");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 50)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        requestStartupInit();
    }

    public KnowledgeRepoMaintenanceTaskType getCurrentTaskType() {
        return currentTaskType;
    }

    public boolean isExclusiveSyncActive() {
        return exclusiveGate.get();
    }

    public boolean isFullRebuildRunning() {
        return fullRebuildRunning.get();
    }

    public boolean isMaintenanceActive() {
        KnowledgeRepoMaintenanceTaskType type = currentTaskType;
        return type != KnowledgeRepoMaintenanceTaskType.IDLE
                && type != KnowledgeRepoMaintenanceTaskType.FAILED;
    }

    public long getCurrentGeneration() {
        return exclusiveGeneration.get();
    }

    public String getLastFailureMessage() {
        return lastFailureMessage;
    }

    /**
     * 启动初始化：probe → 增量同步（检测到 Embedding 与 Milvus 不一致时 exclusive 完全重建）→ 启动目录监听。
     */
    public void requestStartupInit() {
        if (!properties.isStartupSyncEnabled()) {
            log.info("知识库启动同步已关闭，跳过启动初始化同步");
            setTaskType(KnowledgeRepoMaintenanceTaskType.IDLE);
            startWatchIfEnabled();
            return;
        }
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null) {
            log.warn("知识库根目录未配置，跳过维护初始化");
            return;
        }
        log.info("应用启动知识库初始化");
        submitMaintenanceTask(KnowledgeRepoMaintenanceTaskType.INITIALIZING, this::runStartupInit);
    }

    /**
     * 文件监听或内部触发的增量同步。
     */
    public void requestIncrementalSync(String trigger) {
        if (isExclusiveSyncActive()) {
            log.debug("跳过增量同步({})：exclusive 门禁生效", trigger);
            return;
        }
        submitMaintenanceTask(KnowledgeRepoMaintenanceTaskType.INCREMENTAL_SYNC,
                () -> runIncrementalSync(trigger, false));
    }

    /**
     * 管理端手动同步入口（异步提交，立即返回）。
     */
    public KnowledgeRepoSyncOutcome syncNowAsync(boolean fullRebuild) {
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null) {
            return KnowledgeRepoSyncOutcome.fail("知识库根目录未配置");
        }
        if (!Files.exists(rootPath)) {
            return KnowledgeRepoSyncOutcome.fail("知识库根目录不存在: " + rootPath.toAbsolutePath().normalize());
        }
        if (isExclusiveSyncActive() && !fullRebuild) {
            return KnowledgeRepoSyncOutcome.fail("知识库 exclusive 重建进行中，请稍后重试");
        }
        Future<?> future;
        if (fullRebuild) {
            long gen = claimExclusiveGeneration();
            activateExclusiveGateAndStopPrevious();
            future = submitMaintenanceTaskAndGetFuture(KnowledgeRepoMaintenanceTaskType.FULL_REBUILD,
                    () -> runManualFullRebuild(gen));
        } else {
            future = submitMaintenanceTaskAndGetFuture(KnowledgeRepoMaintenanceTaskType.INCREMENTAL_SYNC,
                    () -> runIncrementalSync("manual-api", true));
        }
        if (future == null) {
            return KnowledgeRepoSyncOutcome.fail("未能提交知识库维护任务");
        }
        return KnowledgeRepoSyncOutcome.accepted("已提交后台知识库同步");
    }

    /**
     * 聚合维护协调器与同步进度快照，供管理端轮询。
     */
    public KnowledgeRepoSyncStatusSnapshot snapshotSyncStatus() {
        KnowledgeRepoSyncProgressTracker.Snapshot progress = progressTracker.snapshot();
        return new KnowledgeRepoSyncStatusSnapshot(
                currentTaskType,
                isMaintenanceActive(),
                isFullRebuildRunning(),
                isExclusiveSyncActive(),
                lastFailureMessage,
                progress.phase(),
                progress.totalCount(),
                progress.processedCount(),
                progress.currentFilePath(),
                progress.files()
        );
    }

    /**
     * 管理端手动同步入口（阻塞等待完成，保留供内部或测试使用）。
     */
    public KnowledgeRepoSyncOutcome syncNowAndAwait(Duration timeout, boolean fullRebuild) {
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null) {
            return KnowledgeRepoSyncOutcome.fail("知识库根目录未配置");
        }
        if (!Files.exists(rootPath)) {
            return KnowledgeRepoSyncOutcome.fail("知识库根目录不存在: " + rootPath.toAbsolutePath().normalize());
        }
        if (isExclusiveSyncActive() && !fullRebuild) {
            return KnowledgeRepoSyncOutcome.fail("知识库 exclusive 重建进行中，请稍后重试");
        }
        try {
            Future<?> future;
            if (fullRebuild) {
                long gen = claimExclusiveGeneration();
                activateExclusiveGateAndStopPrevious();
                future = submitMaintenanceTaskAndGetFuture(KnowledgeRepoMaintenanceTaskType.FULL_REBUILD,
                        () -> runManualFullRebuild(gen));
            } else {
                future = submitMaintenanceTaskAndGetFuture(KnowledgeRepoMaintenanceTaskType.INCREMENTAL_SYNC,
                        () -> runIncrementalSync("manual-api", true));
            }
            if (future == null) {
                return KnowledgeRepoSyncOutcome.fail("未能提交知识库维护任务");
            }
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (currentTaskType == KnowledgeRepoMaintenanceTaskType.FAILED && lastFailureMessage != null) {
                return KnowledgeRepoSyncOutcome.fail(lastFailureMessage);
            }
            return KnowledgeRepoSyncOutcome.ok();
        } catch (TimeoutException e) {
            log.warn("知识库维护超时: {} ms", timeout.toMillis());
            return KnowledgeRepoSyncOutcome.fail("知识库同步超时，请稍后重试");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("知识库维护失败", cause);
            return KnowledgeRepoSyncOutcome.fail(cause.getMessage() != null ? cause.getMessage() : "知识库同步失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return KnowledgeRepoSyncOutcome.fail("知识库同步被中断");
        }
    }

    /**
     * Embedding 运行时变更：停止任务 → drop collection → 全量 re-embed。
     */
    public void requestEmbeddingRuntimeRebuild() {
        long generation = claimExclusiveGeneration();
        log.warn("Embedding 运行时变更已排队 exclusive 重建: generation={}", generation);
        activateExclusiveGateAndStopPrevious();
        submitMaintenanceTask(KnowledgeRepoMaintenanceTaskType.FULL_REBUILD,
                () -> runEmbeddingRuntimeRebuild(generation));
    }

    /**
     * 手动重新探测，不 drop collection。
     */
    public void requestProbeOnly() {
        submitMaintenanceTask(KnowledgeRepoMaintenanceTaskType.PROBING, this::runProbeOnly);
    }

    /**
     * 属性变更触发的向量库重载检查（如 RETRIEVE_METRIC_TYPE）。
     */
    public void requestVectorDatabaseReloadCheck() {
        submitMaintenanceTask(KnowledgeRepoMaintenanceTaskType.PROBING, () -> {
            setTaskType(KnowledgeRepoMaintenanceTaskType.PROBING);
            try {
                vectorDatabaseInit.reloadIfConfigurationChanged();
            } catch (Exception e) {
                log.error("向量库重载检查失败", e);
                lastFailureMessage = e.getMessage();
                setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
                return;
            }
            setTaskType(KnowledgeRepoMaintenanceTaskType.IDLE);
        });
    }

    public long claimExclusiveGeneration() {
        return exclusiveGeneration.incrementAndGet();
    }

    public boolean isExclusiveGenerationActive(long generation) {
        return generation > 0 && generation == exclusiveGeneration.get();
    }

    private void activateExclusiveGateAndStopPrevious() {
        synchronized (taskLock) {
            exclusiveGate.set(true);
        }
        cancelCurrentTaskAwaitIdle(STOP_PREVIOUS_TIMEOUT);
    }

    private void endExclusiveIfGeneration(long expectedGeneration) {
        synchronized (taskLock) {
            if (expectedGeneration > 0 && exclusiveGeneration.get() != expectedGeneration) {
                log.debug("跳过释放 exclusive 门禁：代次已更新 expected={}, current={}",
                        expectedGeneration, exclusiveGeneration.get());
                return;
            }
            exclusiveGate.set(false);
            fullRebuildRunning.set(false);
        }
    }

    private void runStartupInit() {
        setTaskType(KnowledgeRepoMaintenanceTaskType.INITIALIZING);
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null || !Files.exists(rootPath)) {
            if (rootPath != null) {
                log.warn("知识库根目录不存在，跳过启动同步: {}", rootPath.toAbsolutePath().normalize());
            }
            setTaskType(KnowledgeRepoMaintenanceTaskType.IDLE);
            startWatchIfEnabled();
            return;
        }
        try {
            String repoHash = lockService.repoRootHash(rootPath);
            boolean executed = lockService.tryWithLock(repoHash, LOCK_WAIT_REBUILD, () -> runStartupInitBody(rootPath));
            if (!executed) {
                log.info("启动知识库初始化跳过：未获取 Redis 维护锁");
            }
        } catch (KnowledgeRepoMaintenanceLockService.KnowledgeRepoMaintenanceLockException e) {
            log.error("启动知识库初始化失败：Redis 锁不可用", e);
            lastFailureMessage = "Redis 不可用，无法执行知识库同步";
            setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
        }
        if (currentTaskType != KnowledgeRepoMaintenanceTaskType.FAILED) {
            setTaskType(KnowledgeRepoMaintenanceTaskType.IDLE);
        }
        startWatchIfEnabled();
    }

    private void runStartupInitBody(Path rootPath) {
        if (!embeddingService.hasActiveEmbeddingConfig()) {
            log.info("未设置当前 Embedding 配置，跳过启动知识库同步");
            return;
        }
        if (!vectorDatabaseInit.probeAndConfigureWithRetry(STARTUP_PROBE_MAX_ATTEMPTS, STARTUP_PROBE_RETRY_INTERVAL)) {
            String probeError = embeddingService.getLastProbeError();
            lastFailureMessage = probeError != null && !probeError.isBlank()
                    ? probeError
                    : "Embedding 探测失败，无法执行知识库同步";
            log.warn("启动知识库 probe 失败: {}", lastFailureMessage);
            setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
            return;
        }
        if (syncService.needsEmbeddingFullRebuild()) {
            runStartupFullRebuildIfNeeded();
            return;
        }
        log.info("应用启动知识库初始化：增量同步");
        syncService.initializeHashCache();
        syncService.executeIncrementalSync(() -> !isExclusiveSyncActive() && !Thread.currentThread().isInterrupted());
    }

    private void runStartupFullRebuildIfNeeded() {
        long generation = claimExclusiveGeneration();
        exclusiveGate.set(true);
        fullRebuildRunning.set(true);
        log.warn("启动检测到 Embedding 与 Milvus 不一致，执行 exclusive 完全重建: generation={}", generation);
        try {
            KnowledgeRepoSyncGuard guard = () -> isExclusiveGenerationActive(generation)
                    && !Thread.currentThread().isInterrupted();
            if (!syncService.executeFullRebuild(guard)) {
                if (!isExclusiveGenerationActive(generation)) {
                    log.info("启动完全重建已被新代次取代: gen={}", generation);
                    return;
                }
                if (currentTaskType != KnowledgeRepoMaintenanceTaskType.FAILED) {
                    String probeError = embeddingService.getLastProbeError();
                    lastFailureMessage = probeError != null && !probeError.isBlank()
                            ? probeError
                            : "完全重建未完整执行（可能已 drop collection），请确认 Embedding/Milvus 可用后重试";
                    setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
                }
            }
        } finally {
            fullRebuildRunning.set(false);
            endExclusiveIfGeneration(generation);
        }
    }

    private void startWatchIfEnabled() {
        if (currentTaskType != KnowledgeRepoMaintenanceTaskType.FAILED && properties.isWatchEnabled()) {
            syncService.startWatch(this::requestIncrementalSync);
        }
    }

    private void runIncrementalSync(String trigger, boolean failIfLockBusy) {
        if (isExclusiveSyncActive()) {
            log.debug("跳过增量同步({})：exclusive 门禁生效", trigger);
            return;
        }
        setTaskType(KnowledgeRepoMaintenanceTaskType.INCREMENTAL_SYNC);
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null || !Files.exists(rootPath)) {
            setTaskType(KnowledgeRepoMaintenanceTaskType.IDLE);
            return;
        }
        try {
            String repoHash = lockService.repoRootHash(rootPath);
            boolean executed = lockService.tryWithLock(repoHash, LOCK_WAIT_SHORT, () -> {
                if (isExclusiveSyncActive()) {
                    return;
                }
                if (!embeddingService.isReady()) {
                    if (!vectorDatabaseInit.probeAndConfigure()) {
                        log.warn("增量同步跳过：Embedding 不可用, trigger={}, probeError={}",
                                trigger, embeddingService.getLastProbeError());
                        return;
                    }
                }
                syncService.executeIncrementalSync(() -> !isExclusiveSyncActive() && !Thread.currentThread().isInterrupted());
            });
            if (!executed) {
                String msg = "其他实例正在维护知识库";
                if (failIfLockBusy) {
                    lastFailureMessage = msg;
                    setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
                } else {
                    log.info("增量同步({})跳过：未获取 Redis 维护锁", trigger);
                    setTaskType(KnowledgeRepoMaintenanceTaskType.IDLE);
                }
                return;
            }
            setTaskType(KnowledgeRepoMaintenanceTaskType.IDLE);
        } catch (KnowledgeRepoMaintenanceLockService.KnowledgeRepoMaintenanceLockException e) {
            log.error("增量同步失败：Redis 锁不可用, trigger={}", trigger, e);
            if (failIfLockBusy) {
                lastFailureMessage = "Redis 不可用，无法执行知识库同步";
                setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
            } else {
                setTaskType(KnowledgeRepoMaintenanceTaskType.IDLE);
            }
        }
    }

    private void runManualFullRebuild(long generation) {
        // 手动完全重建作为兜底：reload Embedding 后再 probe，确保与 DB 当前配置一致。
        runExclusiveFullRebuild(generation, true, false);
    }

    private void runEmbeddingRuntimeRebuild(long generation) {
        runExclusiveFullRebuild(generation, true, false);
    }

    private void runExclusiveFullRebuild(long generation, boolean reloadEmbeddingStack, boolean cancelBeforeRun) {
        if (!isExclusiveGenerationActive(generation)) {
            log.info("完全重建任务已过期: gen={}", generation);
            return;
        }
        setTaskType(KnowledgeRepoMaintenanceTaskType.FULL_REBUILD);
        fullRebuildRunning.set(true);
        exclusiveGate.set(true);
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null || !Files.exists(rootPath)) {
            lastFailureMessage = rootPath == null
                    ? "知识库根目录未配置"
                    : "知识库根目录不存在: " + rootPath.toAbsolutePath().normalize();
            setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
            endExclusiveIfGeneration(generation);
            return;
        }
        try {
            if (cancelBeforeRun) {
                cancelCurrentTaskAwaitIdle(Duration.ofMinutes(2));
            }
            if (!isExclusiveGenerationActive(generation)) {
                return;
            }
            String repoHash = lockService.repoRootHash(rootPath);
            boolean executed = lockService.tryWithLock(repoHash, LOCK_WAIT_REBUILD, () -> {
                if (!isExclusiveGenerationActive(generation)) {
                    return;
                }
                if (reloadEmbeddingStack) {
                    activeProviderHolder.reloadFromDb();
                    embeddingService.rebuildClient();
                }
                KnowledgeRepoSyncGuard guard = () -> isExclusiveGenerationActive(generation)
                        && !Thread.currentThread().isInterrupted();
                if (!syncService.executeFullRebuild(guard)) {
                    if (!isExclusiveGenerationActive(generation)) {
                        log.info("完全重建已被新代次取代: gen={}", generation);
                        return;
                    }
                    if (currentTaskType != KnowledgeRepoMaintenanceTaskType.FAILED) {
                        String probeError = embeddingService.getLastProbeError();
                        lastFailureMessage = probeError != null && !probeError.isBlank()
                                ? probeError
                                : "完全重建未完整执行（可能已 drop collection），请确认 Embedding/Milvus 可用后重试";
                        setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
                    }
                }
            });
            if (!isExclusiveGenerationActive(generation)) {
                log.info("完全重建已被新代次取代: gen={}", generation);
                return;
            }
            if (!executed) {
                lastFailureMessage = "其他实例正在维护知识库，无法执行完全重建";
                setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
            } else if (currentTaskType != KnowledgeRepoMaintenanceTaskType.FAILED) {
                setTaskType(KnowledgeRepoMaintenanceTaskType.IDLE);
            }
        } catch (KnowledgeRepoMaintenanceLockService.KnowledgeRepoMaintenanceLockException e) {
            if (!isExclusiveGenerationActive(generation)) {
                log.info("完全重建已被新代次取代: gen={}", generation);
                return;
            }
            log.error("完全重建失败：Redis 锁不可用, gen={}", generation, e);
            lastFailureMessage = "Redis 不可用，无法执行完全重建";
            setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
        } finally {
            fullRebuildRunning.set(false);
            endExclusiveIfGeneration(generation);
        }
    }

    private void runProbeOnly() {
        if (isExclusiveSyncActive()) {
            log.debug("跳过 probe：exclusive 门禁生效");
            return;
        }
        setTaskType(KnowledgeRepoMaintenanceTaskType.PROBING);
        runProbeOnlyBody();
        if (currentTaskType != KnowledgeRepoMaintenanceTaskType.FAILED) {
            setTaskType(KnowledgeRepoMaintenanceTaskType.IDLE);
        }
    }

    private void runProbeOnlyBody() {
        try {
            activeProviderHolder.reloadFromDb();
            embeddingService.rebuildClient();
            if (!vectorDatabaseInit.probeAndConfigure()) {
                lastFailureMessage = embeddingService.getLastProbeError();
                setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
                return;
            }
        } catch (Exception e) {
            log.error("Embedding 探测失败", e);
            lastFailureMessage = e.getMessage();
            setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
        }
    }

    private void submitMaintenanceTask(KnowledgeRepoMaintenanceTaskType taskType, Runnable task) {
        submitMaintenanceTaskAndGetFuture(taskType, task);
    }

    private Future<?> submitMaintenanceTaskAndGetFuture(KnowledgeRepoMaintenanceTaskType taskType, Runnable task) {
        synchronized (taskLock) {
            Future<?> future = maintenanceExecutor.submit(wrapTask(taskType, task));
            currentTaskFuture = future;
            return future;
        }
    }

    private Runnable wrapTask(KnowledgeRepoMaintenanceTaskType taskType, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.debug("维护任务被取消: type={}", taskType);
                    return;
                }
                log.error("知识库维护任务异常: type={}", taskType, e);
                lastFailureMessage = e.getMessage();
                setTaskType(KnowledgeRepoMaintenanceTaskType.FAILED);
            }
        };
    }

    private void cancelCurrentTaskAwaitIdle(Duration timeout) {
        Future<?> futureToAwait;
        synchronized (taskLock) {
            futureToAwait = currentTaskFuture;
            if (futureToAwait != null) {
                futureToAwait.cancel(true);
                currentTaskFuture = null;
            }
            maintenanceExecutor.getQueue().clear();
        }
        if (futureToAwait != null) {
            try {
                futureToAwait.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (CancellationException e) {
                log.debug("维护任务已取消");
            } catch (TimeoutException e) {
                log.warn("等待维护任务空闲超时: {} ms", timeout.toMillis());
            } catch (ExecutionException e) {
                log.debug("被取消的维护任务异常结束", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (maintenanceExecutor.getActiveCount() > 0 && System.nanoTime() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void setTaskType(KnowledgeRepoMaintenanceTaskType taskType) {
        this.currentTaskType = taskType;
        if (taskType != KnowledgeRepoMaintenanceTaskType.FAILED) {
            lastFailureMessage = null;
        }
    }

    @PreDestroy
    public void destroy() {
        cancelCurrentTaskAwaitIdle(Duration.ofMinutes(1));
        maintenanceExecutor.shutdownNow();
        syncService.shutdownWatch();
    }
}
