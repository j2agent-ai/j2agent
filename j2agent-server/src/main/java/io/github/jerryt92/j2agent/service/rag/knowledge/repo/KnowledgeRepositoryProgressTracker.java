package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按知识库隔离的文件处理进度，供列表轮询。不写入全局维护 tracker。
 */
@Component
public class KnowledgeRepositoryProgressTracker {

    /**
     * 某库本轮已处理文件数与待处理总数。
     */
    public record Snapshot(int processed, int total) {
    }

    private static final class Counters {
        private final int total;
        private final AtomicInteger processed = new AtomicInteger();

        private Counters(int total) {
            this.total = Math.max(total, 0);
        }
    }

    private final ConcurrentHashMap<String, Counters> byRepoCode = new ConcurrentHashMap<>();

    /**
     * 登记本轮待处理文件总数；processed 从 0 开始。
     */
    public void begin(String repoCode, int total) {
        if (repoCode == null || repoCode.isBlank()) {
            return;
        }
        byRepoCode.put(repoCode, new Counters(total));
    }

    /**
     * 本库完成一个文件（删除或写入）后递增。
     */
    public void increment(String repoCode) {
        Counters counters = byRepoCode.get(repoCode);
        if (counters == null) {
            return;
        }
        counters.processed.updateAndGet(value -> Math.min(value + 1, counters.total));
    }

    /**
     * 进行中则返回快照；无任务时返回 null。
     */
    public Snapshot snapshot(String repoCode) {
        if (repoCode == null || repoCode.isBlank()) {
            return null;
        }
        Counters counters = byRepoCode.get(repoCode);
        if (counters == null) {
            return null;
        }
        return new Snapshot(Math.min(counters.processed.get(), counters.total), counters.total);
    }

    /**
     * 本库任务结束时清除进度，避免泄漏到下一轮。
     */
    public void clear(String repoCode) {
        if (repoCode == null || repoCode.isBlank()) {
            return;
        }
        byRepoCode.remove(repoCode);
    }
}
