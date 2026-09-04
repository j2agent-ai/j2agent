package io.github.jerryt92.j2agent.service.security;

import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.mapper.ext.ResourcePermissionMapper;
import io.github.jerryt92.j2agent.model.po.ResourcePermissionRow;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * All database access for a user's grants happens after acquiring the same user lock.
 */
@Service
public class ResourcePermissionCache {
    private static final long TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    /**
     * Redis sorted-set scores must be finite; permanent grants sort after expiry timestamps.
     */
    private static final double PERMANENT_SCORE = Double.MAX_VALUE;
    private final RedissonClient redis;
    private final ResourcePermissionMapper permissionMapper;
    private final TransactionTemplate transactions;
    private final RedisKeyNamespaces namespaces;

    public ResourcePermissionCache(RedissonClient redis, ResourcePermissionMapper permissionMapper,
                                   PlatformTransactionManager manager, RedisKeyNamespaces namespaces) {
        this.redis = redis;
        this.permissionMapper = permissionMapper;
        this.transactions = new TransactionTemplate(manager);
        this.namespaces = namespaces;
    }

    public record Permissions(Set<String> agents, Set<String> manage, Set<String> read) {
    }

    private String prefix(String uid) {
        return namespaces.key("acl:{" + uid + "}:");
    }

    private List<String> dataKeys(String uid) {
        String p = prefix(uid);
        return List.of(p + "agent:2", p + "kb:1", p + "kb:2", p + "loaded");
    }

    private RBatch batch() {
        return redis.createBatch(BatchOptions.defaults().executionMode(BatchOptions.ExecutionMode.IN_MEMORY_ATOMIC));
    }

    public <T> T locked(String uid, Supplier<T> action) {
        if (uid == null || !uid.trim().matches("[A-Za-z0-9_-]{1,32}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user identity");
        RLock lock = redis.getLock(prefix(uid.trim()) + "lock");
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5, TimeUnit.SECONDS); // Watchdog; no fixed lease time.
            if (!acquired) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Permission cache busy");
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Permission lock interrupted", e);
        } catch (org.redisson.client.RedisException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Permission cache unavailable", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    public Permissions read(String userId) {
        String uid = userId.trim();
        return locked(uid, () -> {
            List<String> keys = dataKeys(uid);
            String marker = redis.<String>getBucket(keys.get(3), StringCodec.INSTANCE).get();
            boolean complete = marker != null;
            if (complete) for (int i = 0; i < 3; i++) {
                if (marker.contains(String.valueOf(i)) && !redis.getScoredSortedSet(keys.get(i)).isExists())
                    complete = false;
            }
            if (!complete) load(uid, keys);
            long now = System.currentTimeMillis();
            RBatch batch = batch();
            List<RFuture<Collection<String>>> values = new ArrayList<>();
            for (int i = 0; i < 3; i++)
                values.add(batch.<String>getScoredSortedSet(keys.get(i), StringCodec.INSTANCE)
                        .valueRangeAsync(now, false, Double.POSITIVE_INFINITY, true));
            expire(batch, keys, now + TTL_MILLIS);
            batch.execute();
            return new Permissions(Set.copyOf(values.get(0).getNow()), Set.copyOf(values.get(1).getNow()),
                    Set.copyOf(values.get(2).getNow()));
        });
    }

    private void load(String uid, List<String> keys) {
        List<Map<String, Double>> sets = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>());
        long now = System.currentTimeMillis();
        for (ResourcePermissionRow row : permissionMapper.selectAgentPermissions(uid, now))
            sets.get(0).put(row.getResourceId(), score(row.getExpiresAt()));
        for (ResourcePermissionRow row : permissionMapper.selectKnowledgePermissions(uid, now)) {
            double score = score(row.getExpiresAt());
            sets.get(2).put(row.getResourceId(), score);
            if (row.getPermissionLevel() == 1) sets.get(1).put(row.getResourceId(), score);
        }
        RBatch batch = batch();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            var set = batch.<String>getScoredSortedSet(keys.get(i), StringCodec.INSTANCE);
            set.deleteAsync();
            if (!sets.get(i).isEmpty()) {
                expected.append(i);
                set.addAllAsync(sets.get(i));
            }
        }
        batch.<String>getBucket(keys.get(3), StringCodec.INSTANCE).setAsync(expected.toString());
        expire(batch, keys, now + TTL_MILLIS);
        batch.execute();
    }

    static double score(Object expiry) {
        return expiry == null ? PERMANENT_SCORE : ((Number) expiry).doubleValue();
    }

    private void expire(RBatch batch, List<String> keys, long at) {
        for (int i = 0; i < 3; i++) batch.getScoredSortedSet(keys.get(i), StringCodec.INSTANCE).expireAtAsync(at);
        batch.getBucket(keys.get(3), StringCodec.INSTANCE).expireAtAsync(at);
    }

    private void clear(String uid) {
        RBatch batch = batch();
        for (String key : dataKeys(uid)) batch.getBucket(key, StringCodec.INSTANCE).deleteAsync();
        batch.execute();
    }

    public void mutate(String userId, Runnable databaseChange) {
        String uid = userId.trim();
        locked(uid, () -> {
            clear(uid); // Must succeed before starting the transaction.
            transactions.executeWithoutResult(status -> databaseChange.run());
            // A crash here is safe: the cache was removed before the commit under the same lock.
            clear(uid);
            return null;
        });
    }

    public void invalidate(String userId) {
        locked(userId.trim(), () -> {
            clear(userId.trim());
            return null;
        });
    }
}
