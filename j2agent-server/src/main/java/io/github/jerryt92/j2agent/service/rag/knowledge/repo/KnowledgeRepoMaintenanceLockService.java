package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.utils.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 知识库维护 Redis 分布式锁：同一 repo 根目录在多实例间互斥执行 Milvus 写任务。
 */
@Slf4j
@Service
public class KnowledgeRepoMaintenanceLockService {

    private final RedissonClient redissonClient;
    private final String lockPrefix;

    public KnowledgeRepoMaintenanceLockService(RedissonClient redissonClient, RedisKeyNamespaces redisKeyNamespaces) {
        this.redissonClient = redissonClient;
        this.lockPrefix = redisKeyNamespaces.key("knowledge-repo:maintenance:lock:");
    }

    /**
     * 将知识库根目录规范化为锁 key 后缀。
     */
    public String repoRootHash(Path rootPath) {
        if (rootPath == null) {
            throw new IllegalArgumentException("知识库根目录不能为空");
        }
        String normalized = rootPath.toAbsolutePath().normalize().toString();
        try {
            return HashUtil.getMessageDigest(normalized.getBytes(), HashUtil.MdAlgorithm.SHA256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算知识库根目录 hash", e);
        }
    }

    public RLock lock(String repoRootHash) {
        return redissonClient.getReadWriteLock(lockPrefix + repoRootHash).writeLock();
    }

    public RLock readLock(String repoRootHash) {
        return redissonClient.getReadWriteLock(lockPrefix + repoRootHash).readLock();
    }

    /**
     * 阻塞获取锁后执行；Redis 不可用时 fail-fast。
     */
    public <T> T withLock(String repoRootHash, Supplier<T> action) {
        RLock lock = lock(repoRootHash);
        try {
            lock.lock();
            return action.get();
        } catch (DataAccessException e) {
            throw new KnowledgeRepoMaintenanceLockException("Redis 不可用，无法获取知识库维护锁", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 尝试获取锁；拿不到锁返回 false，Redis 不可用抛异常。
     */
    public boolean tryWithLock(String repoRootHash, Duration waitTime, Runnable action) {
        RLock lock = lock(repoRootHash);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), -1, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) {
                return false;
            }
            action.run();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RedisException e) {
            if (e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return false;
            }
            throw new KnowledgeRepoMaintenanceLockException("Redis 不可用，无法获取知识库维护锁", e);
        } catch (DataAccessException e) {
            throw new KnowledgeRepoMaintenanceLockException("Redis 不可用，无法获取知识库维护锁", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public static class KnowledgeRepoMaintenanceLockException extends RuntimeException {
        public KnowledgeRepoMaintenanceLockException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
