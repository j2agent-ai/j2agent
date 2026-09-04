package io.github.jerryt92.j2agent.service.security;

import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.mapper.ext.ResourcePermissionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBatch;
import org.redisson.api.RBucket;
import org.redisson.api.RBucketAsync;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScoredSortedSetAsync;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResourcePermissionCacheTest {
    @Test
    void mutationLocksBeforeDatabaseAndInvalidatesOnBothSidesOfCommit() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        RBatch batch = mock(RBatch.class);
        ResourcePermissionMapper mapper = mock(ResourcePermissionMapper.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redis.createBatch(any())).thenReturn(batch);
        when(batch.getBucket(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mock(RBucketAsync.class));
        var status = new SimpleTransactionStatus();
        when(tx.getTransaction(any())).thenReturn(status);
        ResourcePermissionCache cache = new ResourcePermissionCache(redis, mapper, tx, new RedisKeyNamespaces("test"));
        cache.mutate("user1", () -> {
        });
        var order = inOrder(lock, batch, tx);
        order.verify(lock).tryLock(5, TimeUnit.SECONDS);
        order.verify(batch).execute();
        order.verify(tx).getTransaction(any());
        order.verify(tx).commit(status);
        order.verify(batch).execute();
        order.verify(lock).isHeldByCurrentThread();
        order.verify(lock).unlock();
    }

    @Test
    void unavailableLockNeverTouchesDatabase() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        ResourcePermissionMapper mapper = mock(ResourcePermissionMapper.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(false);
        var cache = new ResourcePermissionCache(redis, mapper, mock(PlatformTransactionManager.class), new RedisKeyNamespaces("test"));
        assertThrows(ResponseStatusException.class, () -> cache.read("user1"));
        verifyNoInteractions(mapper);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void cachedReadTouchesAllPermissionKeysAtExactlyOneDeadline() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        RBatch batch = mock(RBatch.class);
        ResourcePermissionMapper mapper = mock(ResourcePermissionMapper.class);
        RBucket marker = mock(RBucket.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redis.getBucket(anyString(), eq(StringCodec.INSTANCE))).thenReturn(marker);
        when(marker.get()).thenReturn("012");
        RScoredSortedSet exists = mock(RScoredSortedSet.class);
        when(redis.getScoredSortedSet(anyString())).thenReturn(exists);
        when(exists.isExists()).thenReturn(true);
        when(redis.createBatch(any())).thenReturn(batch);
        RScoredSortedSetAsync set = mock(RScoredSortedSetAsync.class);
        RFuture future = mock(RFuture.class);
        when(batch.getScoredSortedSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(set);
        when(set.valueRangeAsync(anyDouble(), eq(false), eq(Double.POSITIVE_INFINITY), eq(true))).thenReturn(future);
        when(future.getNow()).thenReturn(List.of("resource"));
        RBucketAsync bucket = mock(RBucketAsync.class);
        when(batch.getBucket(anyString(), eq(StringCodec.INSTANCE))).thenReturn(bucket);
        var cache = new ResourcePermissionCache(redis, mapper, mock(PlatformTransactionManager.class), new RedisKeyNamespaces("test"));
        var result = cache.read("user1");
        assertEquals(Set.of("resource"), result.read());
        ArgumentCaptor<Long> deadlines = ArgumentCaptor.forClass(Long.class);
        verify(set, times(3)).expireAtAsync(deadlines.capture());
        long deadline = deadlines.getValue();
        assertEquals(1, new HashSet<>(deadlines.getAllValues()).size());
        verify(bucket).expireAtAsync(deadline);
        verifyNoInteractions(mapper);
    }

    @Test
    void permanentGrantAndExpiryAreIndependentOfCacheTtl() {
        assertEquals(Double.MAX_VALUE, ResourcePermissionCache.score(null));
        assertEquals(1234d, ResourcePermissionCache.score(1234L));
    }
}
