package io.github.jerryt92.j2agent.service.llm.queue;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * WebSocket 聊天输入队列配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "j2agent.chat-queue")
public class ChatQueueProperties {

    /**
     * 是否启用 Redis 输入队列；关闭时 Controller 回退为直接调用 ChatService。
     */
    private boolean enabled = true;

    /**
     * 后台消费线程数量。
     */
    private int workerCount = 8;

    /**
     * 单个 contextId + agentId 允许排队的最大任务数。
     */
    private int maxPendingPerSession = 3;

    /**
     * 空 session ready 标记的短等待时间，避免重复 ready 标记造成空转。
     */
    private int takeTimeoutSeconds = 5;

    /**
     * 空闲队列 key 的兜底 TTL。
     */
    private int queuedTaskTtlSeconds = 300;

    /**
     * 是否缓存运行中 output 快照，用于刷新/断线后的 resume 补发。
     */
    private boolean outputCacheEnabled = false;

    /**
     * 运行中 output 快照缓存 TTL；回答完成后仍会主动删除，TTL 只是兜底。
     */
    private int outputCacheTtlSeconds = 300;
}
