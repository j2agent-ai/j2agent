package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.config.rag.VectorDatabaseInit;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.config.provider.ActiveProviderHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRepoMaintenanceCoordinatorTest {

    @Mock
    private KnowledgeRepoProperties properties;
    @Mock
    private KnowledgeRepoMetadataService metadataService;
    @Mock
    private KnowledgeRepoSyncService syncService;
    @Mock
    private KnowledgeRepoMaintenanceLockService lockService;
    @Mock
    private VectorDatabaseInit vectorDatabaseInit;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private ActiveProviderHolder activeProviderHolder;
    @Mock
    private KnowledgeRepoSyncProgressTracker progressTracker;

    @TempDir
    Path tempRepo;

    private KnowledgeRepoMaintenanceCoordinator coordinator;

    @BeforeEach
    void setUp() {
        lenient().when(properties.isStartupSyncEnabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.destroy();
        }
    }

    @Test
    void requestStartupInit_whenModelConsistent_runsIncrementalSync() throws Exception {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(lockService.repoRootHash(tempRepo)).thenReturn("repo-hash");
        when(embeddingService.hasActiveEmbeddingConfig()).thenReturn(true);
        when(vectorDatabaseInit.probeAndConfigureWithRetry(any(Integer.class), any(Duration.class))).thenReturn(true);
        when(syncService.needsEmbeddingFullRebuild()).thenReturn(false);
        CountDownLatch incrementalDone = new CountDownLatch(1);
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(2);
            action.run();
            return true;
        }).when(lockService).tryWithLock(eq("repo-hash"), any(), any());
        doAnswer(invocation -> {
            incrementalDone.countDown();
            return null;
        }).when(syncService).executeIncrementalSync(any());

        coordinator = newCoordinator();
        coordinator.requestStartupInit();
        assertTrue(incrementalDone.await(5, TimeUnit.SECONDS));

        verify(syncService).initializeHashCache();
        verify(syncService).executeIncrementalSync(any());
        verify(syncService, never()).executeFullRebuild(any());
    }

    @Test
    void requestStartupInit_whenModelInconsistent_runsFullRebuild() throws Exception {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(lockService.repoRootHash(tempRepo)).thenReturn("repo-hash");
        when(embeddingService.hasActiveEmbeddingConfig()).thenReturn(true);
        when(vectorDatabaseInit.probeAndConfigureWithRetry(any(Integer.class), any(Duration.class))).thenReturn(true);
        when(syncService.needsEmbeddingFullRebuild()).thenReturn(true);
        CountDownLatch rebuildDone = new CountDownLatch(1);
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(2);
            action.run();
            return true;
        }).when(lockService).tryWithLock(eq("repo-hash"), any(), any());
        when(syncService.executeFullRebuild(any())).thenAnswer(invocation -> {
            rebuildDone.countDown();
            return true;
        });

        coordinator = newCoordinator();
        coordinator.requestStartupInit();
        assertTrue(rebuildDone.await(5, TimeUnit.SECONDS));

        verify(syncService).executeFullRebuild(any());
        verify(syncService, never()).initializeHashCache();
        verify(syncService, never()).executeIncrementalSync(any());
    }

    @Test
    void requestStartupInit_whenProbeFails_doesNotStartWatch() throws Exception {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(lockService.repoRootHash(tempRepo)).thenReturn("repo-hash");
        when(embeddingService.hasActiveEmbeddingConfig()).thenReturn(true);
        when(embeddingService.getLastProbeError()).thenReturn("Embedding 不可用");
        when(vectorDatabaseInit.probeAndConfigureWithRetry(any(Integer.class), any(Duration.class))).thenReturn(false);
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(2);
            action.run();
            return true;
        }).when(lockService).tryWithLock(eq("repo-hash"), any(), any());

        coordinator = newCoordinator();
        coordinator.requestStartupInit();
        Thread.sleep(500);

        assertEquals(KnowledgeRepoMaintenanceTaskType.FAILED, coordinator.getCurrentTaskType());
        verify(syncService, never()).startWatch(any());
    }

    @Test
    void requestStartupInit_whenStartupSyncDisabled_skipsSync() throws Exception {
        when(properties.isStartupSyncEnabled()).thenReturn(false);

        coordinator = newCoordinator();
        coordinator.requestStartupInit();
        Thread.sleep(200);

        verify(metadataService, never()).getRepoRootPath();
        verify(lockService, never()).tryWithLock(any(), any(), any());
        verify(syncService, never()).initializeHashCache();
        verify(syncService, never()).executeIncrementalSync(any());
        verify(syncService, never()).executeFullRebuild(any());
    }

    @Test
    void syncNowAndAwait_whenExclusiveActive_incrementalStillBusy() {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        coordinator = newCoordinator();
        coordinator.requestEmbeddingRuntimeRebuild();
        assertTrue(coordinator.isExclusiveSyncActive());

        KnowledgeRepoSyncOutcome outcome = coordinator.syncNowAndAwait(Duration.ofSeconds(1), false);
        assertFalse(outcome.succeeded());
        assertTrue(outcome.message().contains("exclusive"));
    }

    @Test
    void syncNowAndAwait_fullRebuild_supersedesExclusiveRebuild() throws Exception {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(lockService.repoRootHash(tempRepo)).thenReturn("repo-hash");
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(2);
            action.run();
            return true;
        }).when(lockService).tryWithLock(eq("repo-hash"), any(), any());

        AtomicInteger rebuildCalls = new AtomicInteger();
        CountDownLatch firstRebuildRunning = new CountDownLatch(1);
        when(syncService.executeFullRebuild(any())).thenAnswer(invocation -> {
            KnowledgeRepoSyncGuard guard = invocation.getArgument(0);
            if (rebuildCalls.incrementAndGet() == 1) {
                firstRebuildRunning.countDown();
                while (guard.shouldContinue()) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return false;
            }
            return true;
        });

        coordinator = newCoordinator();
        coordinator.requestEmbeddingRuntimeRebuild();
        assertTrue(firstRebuildRunning.await(5, TimeUnit.SECONDS));

        KnowledgeRepoSyncOutcome outcome = coordinator.syncNowAndAwait(Duration.ofSeconds(10), true);

        assertTrue(outcome.succeeded());
        assertEquals(2, rebuildCalls.get());
        assertNotEquals(KnowledgeRepoMaintenanceTaskType.FAILED, coordinator.getCurrentTaskType());
    }

    @Test
    void requestEmbeddingRuntimeRebuild_whenSuperseded_doesNotMarkFailed() throws Exception {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(lockService.repoRootHash(tempRepo)).thenReturn("repo-hash");
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(2);
            action.run();
            return true;
        }).when(lockService).tryWithLock(eq("repo-hash"), any(), any());

        CountDownLatch firstRebuildRunning = new CountDownLatch(1);
        when(syncService.executeFullRebuild(any())).thenAnswer(invocation -> {
            KnowledgeRepoSyncGuard guard = invocation.getArgument(0);
            firstRebuildRunning.countDown();
            while (guard.shouldContinue()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return false;
        }).thenReturn(true);

        coordinator = newCoordinator();
        coordinator.requestEmbeddingRuntimeRebuild();
        assertTrue(firstRebuildRunning.await(5, TimeUnit.SECONDS));

        coordinator.requestEmbeddingRuntimeRebuild();
        Thread.sleep(500);

        assertNotEquals(KnowledgeRepoMaintenanceTaskType.FAILED, coordinator.getCurrentTaskType());
    }

    @Test
    void syncNowAndAwait_whenRepoRootMissing_returnsFail() {
        coordinator = newCoordinator();
        when(metadataService.getRepoRootPath()).thenReturn(null);

        KnowledgeRepoSyncOutcome outcome = coordinator.syncNowAndAwait(Duration.ofSeconds(1), false);
        assertFalse(outcome.succeeded());
        assertTrue(outcome.message().contains("根目录"));
    }

    @Test
    void syncNowAsync_whenRepoRootMissing_returnsFailImmediately() {
        coordinator = newCoordinator();
        when(metadataService.getRepoRootPath()).thenReturn(null);

        KnowledgeRepoSyncOutcome outcome = coordinator.syncNowAsync(false);
        assertFalse(outcome.succeeded());
        assertTrue(outcome.message().contains("根目录"));
    }

    @Test
    void syncNowAsync_whenAccepted_returnsImmediatelyWhileTaskRuns() throws Exception {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(lockService.repoRootHash(tempRepo)).thenReturn("repo-hash");
        when(embeddingService.isReady()).thenReturn(true);
        CountDownLatch incrementalStarted = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(2);
            action.run();
            return true;
        }).when(lockService).tryWithLock(eq("repo-hash"), any(), any());
        doAnswer(invocation -> {
            incrementalStarted.countDown();
            assertTrue(allowFinish.await(5, TimeUnit.SECONDS));
            return null;
        }).when(syncService).executeIncrementalSync(any());

        coordinator = newCoordinator();
        long start = System.nanoTime();
        KnowledgeRepoSyncOutcome outcome = coordinator.syncNowAsync(false);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(outcome.succeeded());
        assertTrue(outcome.message().contains("后台"));
        assertTrue(elapsedMs < 2000);
        assertTrue(incrementalStarted.await(5, TimeUnit.SECONDS));
        allowFinish.countDown();
        Thread.sleep(300);
    }

    @Test
    void snapshotSyncStatus_delegatesToProgressTracker() {
        when(progressTracker.snapshot()).thenReturn(new KnowledgeRepoSyncProgressTracker.Snapshot(
                KnowledgeRepoSyncPhase.UPSERTING,
                3,
                1,
                "doc/a.md",
                List.of(new KnowledgeRepoSyncProgressTracker.FileProgress(
                        "doc/a.md",
                        KnowledgeRepoSyncFileChangeType.ADDED,
                        KnowledgeRepoSyncFileStatus.IN_PROGRESS,
                        "kb",
                        null,
                        null))));
        coordinator = newCoordinator();

        KnowledgeRepoSyncStatusSnapshot snapshot = coordinator.snapshotSyncStatus();
        assertEquals(KnowledgeRepoSyncPhase.UPSERTING, snapshot.phase());
        assertEquals(3, snapshot.totalCount());
        assertEquals(1, snapshot.processedCount());
        assertEquals("doc/a.md", snapshot.currentFilePath());
        assertEquals(1, snapshot.files().size());
    }

    @Test
    void snapshotSyncStatus_whenTaskInterrupted_reportsIdleInsteadOfRunning() throws Exception {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(lockService.repoRootHash(tempRepo)).thenReturn("repo-hash");
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(2);
            action.run();
            return true;
        }).when(lockService).tryWithLock(eq("repo-hash"), any(), any());
        when(progressTracker.snapshot()).thenReturn(new KnowledgeRepoSyncProgressTracker.Snapshot(
                KnowledgeRepoSyncPhase.UPSERTING, 3, 1, "doc/a.md", List.of()));
        CountDownLatch rebuildEntered = new CountDownLatch(1);
        // 模拟进程退出/中断：任务提前抛出，不会走到把状态置回 IDLE 的分支
        when(syncService.executeFullRebuild(any())).thenAnswer(invocation -> {
            rebuildEntered.countDown();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Maintenance interrupted");
        });

        coordinator = newCoordinator();
        coordinator.requestEmbeddingRuntimeRebuild();
        assertTrue(rebuildEntered.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);

        assertFalse(coordinator.isMaintenanceActive());
        assertFalse(coordinator.isExclusiveSyncActive());
        assertFalse(coordinator.isFullRebuildRunning());
        assertEquals(KnowledgeRepoMaintenanceTaskType.IDLE, coordinator.snapshotSyncStatus().taskType());
    }

    @Test
    void isExclusiveGenerationActive_tracksLatestGeneration() {
        coordinator = newCoordinator();
        long gen1 = coordinator.claimExclusiveGeneration();
        assertTrue(coordinator.isExclusiveGenerationActive(gen1));

        long gen2 = coordinator.claimExclusiveGeneration();
        assertFalse(coordinator.isExclusiveGenerationActive(gen1));
        assertTrue(coordinator.isExclusiveGenerationActive(gen2));
    }

    private KnowledgeRepoMaintenanceCoordinator newCoordinator() {
        return new KnowledgeRepoMaintenanceCoordinator(
                properties,
                metadataService,
                syncService,
                lockService,
                vectorDatabaseInit,
                embeddingService,
                activeProviderHolder,
                progressTracker);
    }
}
