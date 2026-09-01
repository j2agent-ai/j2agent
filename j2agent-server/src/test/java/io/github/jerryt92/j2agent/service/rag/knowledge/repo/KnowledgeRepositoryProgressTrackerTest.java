package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 按库进度计数隔离，多库并行不得互相覆盖。
 */
class KnowledgeRepositoryProgressTrackerTest {

    @Test
    void parallelReposDoNotOverwriteEachOther() {
        KnowledgeRepositoryProgressTracker tracker = new KnowledgeRepositoryProgressTracker();
        tracker.begin("a", 10);
        tracker.begin("b", 5);
        tracker.increment("a");
        tracker.increment("a");
        tracker.increment("b");

        KnowledgeRepositoryProgressTracker.Snapshot snapshotA = tracker.snapshot("a");
        KnowledgeRepositoryProgressTracker.Snapshot snapshotB = tracker.snapshot("b");
        assertEquals(2, snapshotA.processed());
        assertEquals(10, snapshotA.total());
        assertEquals(1, snapshotB.processed());
        assertEquals(5, snapshotB.total());

        tracker.clear("a");
        assertNull(tracker.snapshot("a"));
        assertEquals(5, tracker.snapshot("b").total());
        assertEquals(1, tracker.snapshot("b").processed());
    }

    @Test
    void idleRepoHasNoSnapshot() {
        KnowledgeRepositoryProgressTracker tracker = new KnowledgeRepositoryProgressTracker();
        assertNull(tracker.snapshot("missing"));
        assertNull(tracker.snapshot(null));
        tracker.increment("missing");
        tracker.clear("missing");
        assertNull(tracker.snapshot("missing"));
    }
}
