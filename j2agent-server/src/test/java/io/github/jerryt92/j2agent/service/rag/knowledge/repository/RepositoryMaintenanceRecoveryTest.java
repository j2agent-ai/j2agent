package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.mapper.ext.RepositoryMaintenanceMapper;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMaintenanceLockService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验服务重启后遗留的按库维护状态能被回收。
 */
@ExtendWith(MockitoExtension.class)
class RepositoryMaintenanceRecoveryTest {

    @Mock
    private KnowledgeRepoSyncService sync;
    @Mock
    private KnowledgeRepoMetadataService metadata;
    @Mock
    private KnowledgeRepoMaintenanceLockService locks;
    @Mock
    private KnowledgeRepositoryMapper mapper;
    @Mock
    private RepositoryMaintenanceMapper taskMapper;
    @Mock
    private RedissonClient redis;
    @Mock
    private RedisKeyNamespaces keys;
    @Mock
    private RLock repositoryLock;

    private RepositoryMaintenanceService service;

    @BeforeEach
    void setUp() {
        lenient().when(keys.key(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(redis.getLock("knowledge-repo:repository:repo-1")).thenReturn(repositoryLock);
        lenient().when(taskMapper.findStaleTasks()).thenReturn(List.of(Map.of(
                "id", "repo-1",
                "repo_code", "docs",
                "status", "SYNCING",
                "last_active", 0L)));
        service = new RepositoryMaintenanceService(sync, metadata, locks, mapper, taskMapper, redis, keys);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void recoverInterruptedTasks_whenNobodyHoldsRepositoryLock_resetsStatusAndTasks() {
        when(repositoryLock.isLocked()).thenReturn(false);

        service.recoverInterruptedTasks();

        verify(taskMapper).failQueuedTasks(eq("repo-1"), eq("任务已被服务重启中断"), anyLong());
        verify(mapper).updateStatus(eq("repo-1"), eq("IDLE"), anyString(), anyLong());
    }

    @Test
    void recoverInterruptedTasks_whenRepositoryLockHeld_keepsRunningTaskUntouched() {
        when(repositoryLock.isLocked()).thenReturn(true);

        service.recoverInterruptedTasks();

        verify(taskMapper, never()).failQueuedTasks(anyString(), anyString(), anyLong());
        verify(mapper, never()).updateStatus(anyString(), anyString(), any(), anyLong());
    }
}
