package io.github.jerryt92.j2agent.service.security;

import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResourcePermissionCacheTest {
    @Test void mutationLocksBeforeDatabaseAndInvalidatesOnBothSidesOfCommit() throws Exception {
        RedissonClient redis=mock(RedissonClient.class); RLock lock=mock(RLock.class); RBatch batch=mock(RBatch.class);
        JdbcTemplate jdbc=mock(JdbcTemplate.class); PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock(5,TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true); when(redis.createBatch(any())).thenReturn(batch);
        when(batch.getBucket(anyString(),eq(StringCodec.INSTANCE))).thenReturn(mock(RBucketAsync.class));
        var status=new SimpleTransactionStatus(); when(tx.getTransaction(any())).thenReturn(status);
        ResourcePermissionCache cache=new ResourcePermissionCache(redis,jdbc,tx,new RedisKeyNamespaces("test"));
        cache.mutate("user1",()->jdbc.update("DELETE grants"));
        var order=inOrder(lock,batch,tx,jdbc);
        order.verify(lock).tryLock(5,TimeUnit.SECONDS);
        order.verify(batch).execute();
        order.verify(tx).getTransaction(any());
        order.verify(jdbc).update("DELETE grants");
        order.verify(tx).commit(status);
        order.verify(batch).execute();
        order.verify(lock).isHeldByCurrentThread(); order.verify(lock).unlock();
    }

    @Test void unavailableLockNeverTouchesDatabase() throws Exception {
        RedissonClient redis=mock(RedissonClient.class); RLock lock=mock(RLock.class); JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock(5,TimeUnit.SECONDS)).thenReturn(false);
        var cache=new ResourcePermissionCache(redis,jdbc,mock(PlatformTransactionManager.class),new RedisKeyNamespaces("test"));
        assertThrows(ResponseStatusException.class,()->cache.read("user1")); verifyNoInteractions(jdbc);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    @Test void cachedReadTouchesAllPermissionKeysAtExactlyOneDeadline() throws Exception {
        RedissonClient redis=mock(RedissonClient.class); RLock lock=mock(RLock.class); RBatch batch=mock(RBatch.class);
        JdbcTemplate jdbc=mock(JdbcTemplate.class); RBucket marker=mock(RBucket.class);
        when(redis.getLock(anyString())).thenReturn(lock);when(lock.tryLock(5,TimeUnit.SECONDS)).thenReturn(true);when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redis.getBucket(anyString(),eq(StringCodec.INSTANCE))).thenReturn(marker);when(marker.get()).thenReturn("012");
        RScoredSortedSet exists=mock(RScoredSortedSet.class);when(redis.getScoredSortedSet(anyString())).thenReturn(exists);when(exists.isExists()).thenReturn(true);
        when(redis.createBatch(any())).thenReturn(batch);
        RScoredSortedSetAsync set=mock(RScoredSortedSetAsync.class);RFuture future=mock(RFuture.class);
        when(batch.getScoredSortedSet(anyString(),eq(StringCodec.INSTANCE))).thenReturn(set);
        when(set.valueRangeAsync(anyDouble(),eq(false),eq(Double.POSITIVE_INFINITY),eq(true))).thenReturn(future);
        when(future.getNow()).thenReturn(List.of("resource"));
        RBucketAsync bucket=mock(RBucketAsync.class);when(batch.getBucket(anyString(),eq(StringCodec.INSTANCE))).thenReturn(bucket);
        var cache=new ResourcePermissionCache(redis,jdbc,mock(PlatformTransactionManager.class),new RedisKeyNamespaces("test"));
        var result=cache.read("user1");assertEquals(Set.of("resource"),result.read());
        ArgumentCaptor<Long> deadlines=ArgumentCaptor.forClass(Long.class);verify(set,times(3)).expireAtAsync(deadlines.capture());
        long deadline=deadlines.getValue();assertEquals(1,new HashSet<>(deadlines.getAllValues()).size());verify(bucket).expireAtAsync(deadline);
        verifyNoInteractions(jdbc);
    }

    @Test void permanentGrantAndExpiryAreIndependentOfCacheTtl() {
        assertEquals(Double.POSITIVE_INFINITY,ResourcePermissionCache.score(null));
        assertEquals(1234d,ResourcePermissionCache.score(1234L));
    }
}
