package io.github.jerryt92.j2agent.service.llm.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 聊天输入队列管理器。
 *
 * <p>输入侧使用“两级队列”：
 * session queue 保存真实 {@link ChatTurnInputTask}；
 * ready queue 只保存 {@code contextId + agentId}，用于唤醒 worker。
 * 这样可以按会话串行消费，又避免 worker 扫描大量 Redis key。</p>
 */
@Service
public class ChatInputQueueManager {

    private static final String READY_QUEUE = "chat:input:ready";
    private static final String SESSION_QUEUE_PREFIX = "chat:input:";

    private final RedissonClient redissonClient;
    private final RedisKeyNamespaces redisKeyNamespaces;
    private final ChatQueueProperties properties;
    private final RBlockingQueue<String> readyQueue;
    private final TypedJsonJacksonCodec taskCodec;

    public ChatInputQueueManager(RedissonClient redissonClient,
                                 RedisKeyNamespaces redisKeyNamespaces,
                                 ChatQueueProperties properties,
                                 ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.redisKeyNamespaces = redisKeyNamespaces;
        this.properties = properties;
        this.readyQueue = redissonClient.getBlockingQueue(
                redisKeyNamespaces.key(READY_QUEUE), StringCodec.INSTANCE);
        this.taskCodec = new TypedJsonJacksonCodec(ChatTurnInputTask.class, objectMapper);
    }

    /**
     * 将 UI 输入任务写入 Redis，并投递一次 ready 标记唤醒 worker。
     */
    public ChatEnqueueResult enqueue(ChatTurnInputTask task) {
        if (!properties.isEnabled()) {
            return ChatEnqueueResult.disabled();
        }
        try {
            requireTask(task);
            RBlockingQueue<ChatTurnInputTask> queue = sessionQueue(task.getContextId(), task.getAgentId());
            int maxPending = properties.getMaxPendingPerSession();
            if (maxPending > 0 && queue.size() >= maxPending) {
                return ChatEnqueueResult.queueFull(maxPending);
            }
            if (!queue.offer(task)) {
                return ChatEnqueueResult.unavailable(null);
            }
            queue.expire(Duration.ofSeconds(properties.getQueuedTaskTtlSeconds()));
            readyQueue.offer(ChatCallbackRegistry.sessionKey(task.getContextId(), task.getAgentId()));
            return ChatEnqueueResult.enqueued();
        } catch (Exception e) {
            return ChatEnqueueResult.unavailable(e);
        }
    }

    /**
     * 按会话维度阻塞获取一条任务；保留给测试或定向消费使用。
     */
    public ChatTurnInputTask take(String contextId, String agentId) throws InterruptedException {
        return sessionQueue(contextId, agentId).take();
    }

    /**
     * 查询某个会话当前 pending 数，用于限流和 resume 判断。
     */
    public int size(String contextId, String agentId) {
        return sessionQueue(contextId, agentId).size();
    }

    /**
     * worker 阻塞等待一个有任务的 sessionKey。
     */
    String takeReadySessionKey() throws InterruptedException {
        return readyQueue.take();
    }

    /**
     * 从指定 session queue 拉取一条任务；ready 标记可能重复，因此允许短超时返回 null。
     */
    ChatTurnInputTask poll(String sessionKey) throws InterruptedException {
        SessionKey parsed = parseSessionKey(sessionKey);
        return sessionQueue(parsed.contextId(), parsed.agentId())
                .poll(properties.getTakeTimeoutSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 当前 session 仍有积压时重新投递 ready 标记，保证同会话任务继续被消费。
     */
    void requeueIfPending(String sessionKey) {
        SessionKey parsed = parseSessionKey(sessionKey);
        if (size(parsed.contextId(), parsed.agentId()) > 0) {
            readyQueue.offer(sessionKey);
        }
    }

    /**
     * 生成真实任务队列 key：{@code {app}:chat:input:{contextId}:{agentId}}。
     */
    String queueKey(String contextId, String agentId) {
        return redisKeyNamespaces.key(SESSION_QUEUE_PREFIX + ChatCallbackRegistry.sessionKey(contextId, agentId));
    }

    /**
     * 解析 ready queue 中的 sessionKey。
     */
    static SessionKey parseSessionKey(String sessionKey) {
        if (!StringUtils.isNotBlank(sessionKey)) {
            throw new IllegalArgumentException("sessionKey must not be blank.");
        }
        int sep = sessionKey.lastIndexOf(':');
        if (sep <= 0 || sep >= sessionKey.length() - 1) {
            throw new IllegalArgumentException("Invalid chat session key: " + sessionKey);
        }
        return new SessionKey(sessionKey.substring(0, sep), sessionKey.substring(sep + 1));
    }

    private RBlockingQueue<ChatTurnInputTask> sessionQueue(String contextId, String agentId) {
        return redissonClient.getBlockingQueue(queueKey(contextId, agentId), taskCodec);
    }

    /**
     * 校验入队任务的最小必要字段，避免无效任务进入 Redis 后由 worker 反复失败。
     */
    private static void requireTask(ChatTurnInputTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null.");
        }
        ChatCallbackRegistry.sessionKey(task.getContextId(), task.getAgentId());
        if (!StringUtils.isNotBlank(task.getSubscriptionId())) {
            throw new IllegalArgumentException("subscriptionId must not be blank.");
        }
        if (task.getRequest() == null) {
            throw new IllegalArgumentException("request must not be null.");
        }
    }

    record SessionKey(String contextId, String agentId) {
    }
}
