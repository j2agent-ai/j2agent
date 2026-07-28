package io.github.jerryt92.j2agent.service.llm.queue;

/**
 * 输入任务入队结果。
 *
 * <p>Controller 使用该结果决定是否立即返回失败事件；入队成功后，
 * 真正的 Agent 执行由 {@link ChatInputQueueWorker} 在后台消费。</p>
 */
public record ChatEnqueueResult(Status status, String errorCode, String errorMessage) {

    /**
     * 入队状态。
     */
    public enum Status {
        /** 已写入 Redis 队列，等待 worker 消费。 */
        ENQUEUED,
        /** 队列开关关闭，调用方应回退到直接执行链路。 */
        DISABLED,
        /** 同一 contextId + agentId 下 pending 数超过限制。 */
        QUEUE_FULL,
        /** Redis 或队列服务暂不可用。 */
        UNAVAILABLE
    }

    public static ChatEnqueueResult enqueued() {
        return new ChatEnqueueResult(Status.ENQUEUED, null, null);
    }

    public static ChatEnqueueResult disabled() {
        return new ChatEnqueueResult(Status.DISABLED, null, null);
    }

    public static ChatEnqueueResult queueFull(int maxPendingPerSession) {
        return new ChatEnqueueResult(
                Status.QUEUE_FULL,
                "chat_queue_full",
                "Chat queue is full. maxPendingPerSession=" + maxPendingPerSession);
    }

    public static ChatEnqueueResult unavailable(Throwable cause) {
        String message = cause == null ? "Chat queue is unavailable." : cause.getMessage();
        return new ChatEnqueueResult(Status.UNAVAILABLE, "chat_queue_unavailable", message);
    }
}
