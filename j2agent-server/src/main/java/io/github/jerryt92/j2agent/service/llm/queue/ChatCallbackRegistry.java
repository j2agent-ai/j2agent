package io.github.jerryt92.j2agent.service.llm.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatCallback;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RSetCache;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * WebSocket callback 注册表。
 *
 * <p>真实 {@link ChatCallback} 持有当前 JVM 的 WebSocketSession，不能序列化到 Redis，也不能跨 JVM 发送。
 * 因此 callback 与 close handler 仍保存在本地内存；跨线程/跨节点需要共享的 subscription 元数据与取消标记写入 Redis。</p>
 */
@Component
public class ChatCallbackRegistry {
    private static final String CURRENT_SUBSCRIPTION_PREFIX = "chat:callback:current:";
    private static final String SESSION_SUBSCRIPTIONS_PREFIX = "chat:callback:subscriptions:";
    private static final String CANCELLED_SUBSCRIPTION_PREFIX = "chat:callback:cancelled:";
    private static final String CANCELLED_SESSION_PREFIX = "chat:callback:cancelled-session:";
    private static final String SESSION_CANCEL_TOPIC_KEY = "chat:callback:session-cancel";

    private final RedissonClient redissonClient;
    private final RedisKeyNamespaces redisKeyNamespaces;
    private final ChatQueueProperties properties;
    private final TypedJsonJacksonCodec cancelCodec;
    /** 本机 callback：key = contextId + agentId + subscriptionId。 */
    private final ConcurrentMap<String, ChatCallback<AgentUiEventEnvelope>> callbacks = new ConcurrentHashMap<>();
    /** 本机 session 下全部 callback：用于一个后台任务同时推给多个客户端。 */
    private final ConcurrentMap<String, ConcurrentMap<String, ChatCallback<AgentUiEventEnvelope>>> sessionCallbacks =
            new ConcurrentHashMap<>();
    /** 单连接 close handler，兼容旧的 subscription 级关闭处理。 */
    private final ConcurrentMap<String, Runnable> websocketCloseHandlers = new ConcurrentHashMap<>();
    /** session 级 close handler，主动停止时可让运行节点触发真实取消回调。 */
    private final ConcurrentMap<String, Runnable> sessionWebsocketCloseHandlers = new ConcurrentHashMap<>();
    private Integer cancelListenerId;

    public ChatCallbackRegistry(RedissonClient redissonClient,
                                RedisKeyNamespaces redisKeyNamespaces,
                                ChatQueueProperties properties,
                                ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.redisKeyNamespaces = redisKeyNamespaces;
        this.properties = properties;
        this.cancelCodec = new TypedJsonJacksonCodec(ChatSessionCancelMessage.class, objectMapper);
    }

    /**
     * 订阅 session 级取消消息。任意节点收到 stop 后，其它节点可同步触发本机 close handler。
     */
    @PostConstruct
    public void subscribeSessionCancelTopic() {
        RTopic topic = sessionCancelTopic();
        cancelListenerId = topic.addListener(ChatSessionCancelMessage.class,
                (MessageListener<ChatSessionCancelMessage>) (channel, message) -> {
                    if (message == null
                            || !StringUtils.isNotBlank(message.getContextId())
                            || !StringUtils.isNotBlank(message.getAgentId())) {
                        return;
                    }
                    handleSessionWebsocketClose(message.getContextId(), message.getAgentId());
                });
    }

    @PreDestroy
    public void unsubscribeSessionCancelTopic() {
        if (cancelListenerId != null) {
            sessionCancelTopic().removeListener(cancelListenerId);
            cancelListenerId = null;
        }
    }

    /**
     * 注册一个 WebSocket 观察连接。
     *
     * <p>同一 session 可以存在多个 subscription；后连接不会覆盖先连接。</p>
     */
    public void register(String contextId,
                         String agentId,
                         String subscriptionId,
                         ChatCallback<AgentUiEventEnvelope> callback) {
        String callbackKey = key(contextId, agentId, subscriptionId);
        String sessionKey = sessionKey(contextId, agentId);
        callbacks.put(callbackKey, callback);
        sessionCallbacks.computeIfAbsent(sessionKey, ignored -> new ConcurrentHashMap<>())
                .put(requireText(subscriptionId, "subscriptionId"), callback);
        cancelledSubscriptionBucket(contextId, agentId, subscriptionId).delete();
        sessionSubscriptions(contextId, agentId)
                .add(requireText(subscriptionId, "subscriptionId"),
                        registryTtl().toSeconds(),
                        TimeUnit.SECONDS);
        currentSubscriptionBucket(contextId, agentId)
                .set(requireText(subscriptionId, "subscriptionId"), registryTtl());
    }

    public ChatCallback<AgentUiEventEnvelope> get(String contextId, String agentId, String subscriptionId) {
        return callbacks.get(key(contextId, agentId, subscriptionId));
    }

    public ChatCallback<AgentUiEventEnvelope> getCurrent(String contextId, String agentId) {
        String subscriptionId = getCurrentSubscriptionId(contextId, agentId);
        if (!StringUtils.isNotBlank(subscriptionId)) {
            return null;
        }
        return callbacks.get(key(contextId, agentId, subscriptionId));
    }

    public String getCurrentSubscriptionId(String contextId, String agentId) {
        return currentSubscriptionBucket(contextId, agentId).get();
    }

    /**
     * 列出当前 JVM 内某 session 的全部 callback，用于 session 级广播。
     */
    public List<RegisteredCallback> listLocalCallbacks(String contextId, String agentId) {
        ConcurrentMap<String, ChatCallback<AgentUiEventEnvelope>> sessionMap =
                sessionCallbacks.get(sessionKey(contextId, agentId));
        if (sessionMap == null || sessionMap.isEmpty()) {
            return List.of();
        }
        List<RegisteredCallback> result = new ArrayList<>();
        for (var entry : sessionMap.entrySet()) {
            if (entry.getValue() != null) {
                result.add(new RegisteredCallback(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    /**
     * 注销单个观察连接，同时维护 Redis 中的 subscription 集合与 current 指针。
     */
    public void unregister(String contextId, String agentId, String subscriptionId) {
        String callbackKey = key(contextId, agentId, subscriptionId);
        String sessionKey = sessionKey(contextId, agentId);
        callbacks.remove(callbackKey);
        websocketCloseHandlers.remove(callbackKey);
        ConcurrentMap<String, ChatCallback<AgentUiEventEnvelope>> sessionMap = sessionCallbacks.get(sessionKey);
        if (sessionMap != null) {
            sessionMap.remove(subscriptionId);
            if (sessionMap.isEmpty()) {
                sessionCallbacks.remove(sessionKey, sessionMap);
            }
        }
        sessionSubscriptions(contextId, agentId).remove(subscriptionId);
        RBucket<String> bucket = currentSubscriptionBucket(contextId, agentId);
        String current = bucket.get();
        if (subscriptionId.equals(current)) {
            String nextSubscriptionId = sessionMap == null ? null : sessionMap.keySet().stream().findFirst().orElse(null);
            if (StringUtils.isNotBlank(nextSubscriptionId)) {
                bucket.set(nextSubscriptionId, registryTtl());
            } else {
                bucket.delete();
            }
        }
    }

    public boolean exists(String contextId, String agentId, String subscriptionId) {
        return callbacks.containsKey(key(contextId, agentId, subscriptionId));
    }

    /**
     * 标记单个 subscription 已取消，worker 后续消费到该任务时会丢弃。
     */
    public void markCancelled(String contextId, String agentId, String subscriptionId) {
        cancelledSubscriptionBucket(contextId, agentId, subscriptionId)
                .set("1", Duration.ofSeconds(Math.max(60, properties.getQueuedTaskTtlSeconds())));
    }

    /**
     * 标记整个 session 已取消，并通过 Pub/Sub 通知各节点触发本机取消回调。
     */
    public boolean markSessionCancelled(String contextId, String agentId) {
        RBucket<String> bucket = cancelledSessionBucket(contextId, agentId);
        boolean firstCancel = !bucket.isExists();
        bucket.set("1", Duration.ofSeconds(Math.max(60, properties.getQueuedTaskTtlSeconds())));
        try {
            sessionCancelTopic().publish(new ChatSessionCancelMessage(contextId, agentId, "user interrupt"));
        } catch (Throwable ignored) {
            handleSessionWebsocketClose(contextId, agentId);
        }
        return firstCancel;
    }

    /**
     * 判断任务是否已在连接级或 session 级被取消。
     */
    public boolean isCancelled(String contextId, String agentId, String subscriptionId) {
        return isSessionCancelled(contextId, agentId)
                || cancelledSubscriptionBucket(contextId, agentId, subscriptionId).isExists();
    }

    public boolean isSessionCancelled(String contextId, String agentId) {
        return cancelledSessionBucket(contextId, agentId).isExists();
    }

    public void clearSessionCancelled(String contextId, String agentId) {
        cancelledSessionBucket(contextId, agentId).delete();
    }

    /**
     * 绑定 ChatService 创建的取消回调。普通断开不会直接调用它；主动停止才是权威取消入口。
     */
    public void bindWebsocketCloseHandler(String contextId,
                                          String agentId,
                                          String subscriptionId,
                                          Runnable handler) {
        String key = key(contextId, agentId, subscriptionId);
        String sessionKey = sessionKey(contextId, agentId);
        if (handler == null) {
            websocketCloseHandlers.remove(key);
            sessionWebsocketCloseHandlers.remove(sessionKey);
            return;
        }
        websocketCloseHandlers.put(key, handler);
        sessionWebsocketCloseHandlers.put(sessionKey, handler);
    }

    /**
     * 兼容旧链路：按 subscription 触发 close handler。
     */
    public void handleWebsocketClose(String contextId, String agentId, String subscriptionId) {
        Runnable handler = websocketCloseHandlers.get(key(contextId, agentId, subscriptionId));
        if (handler == null) {
            handler = sessionWebsocketCloseHandlers.get(sessionKey(contextId, agentId));
        }
        if (handler != null) {
            handler.run();
        }
    }

    /**
     * session 级主动停止：同一 contextId + agentId 下所有连接共享同一个运行中 turn。
     */
    public void handleSessionWebsocketClose(String contextId, String agentId) {
        Runnable handler = sessionWebsocketCloseHandlers.get(sessionKey(contextId, agentId));
        if (handler != null) {
            handler.run();
        }
    }

    /**
     * 连接级 key，区分同一 session 下多个客户端。
     */
    public static String key(String contextId, String agentId, String subscriptionId) {
        return requireText(contextId, "contextId") + ":" + requireText(agentId, "agentId")
                + ":" + requireText(subscriptionId, "subscriptionId");
    }

    /**
     * session 级 key，是后台任务、输入串行和主动停止的主要维度。
     */
    public static String sessionKey(String contextId, String agentId) {
        return requireText(contextId, "contextId") + ":" + requireText(agentId, "agentId");
    }

    private static String requireText(String value, String name) {
        if (!StringUtils.isNotBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value.trim();
    }

    private RBucket<String> currentSubscriptionBucket(String contextId, String agentId) {
        return redissonClient.getBucket(
                redisKeyNamespaces.key(CURRENT_SUBSCRIPTION_PREFIX + sessionKey(contextId, agentId)),
                StringCodec.INSTANCE);
    }

    private RSetCache<String> sessionSubscriptions(String contextId, String agentId) {
        return redissonClient.getSetCache(
                redisKeyNamespaces.key(SESSION_SUBSCRIPTIONS_PREFIX + sessionKey(contextId, agentId)),
                StringCodec.INSTANCE);
    }

    private RBucket<String> cancelledSubscriptionBucket(String contextId, String agentId, String subscriptionId) {
        return redissonClient.getBucket(
                redisKeyNamespaces.key(CANCELLED_SUBSCRIPTION_PREFIX + key(contextId, agentId, subscriptionId)),
                StringCodec.INSTANCE);
    }

    private RBucket<String> cancelledSessionBucket(String contextId, String agentId) {
        return redissonClient.getBucket(
                redisKeyNamespaces.key(CANCELLED_SESSION_PREFIX + sessionKey(contextId, agentId)),
                StringCodec.INSTANCE);
    }

    private RTopic sessionCancelTopic() {
        return redissonClient.getTopic(
                redisKeyNamespaces.key(SESSION_CANCEL_TOPIC_KEY),
                cancelCodec);
    }

    /**
     * registry 元数据 TTL。callback 本体在本机内存，Redis 只保存可共享标记。
     */
    private Duration registryTtl() {
        int seconds = Math.max(
                Math.max(properties.getQueuedTaskTtlSeconds(), properties.getOutputCacheTtlSeconds()),
                3600);
        return Duration.ofSeconds(seconds);
    }

    /**
     * 本机已注册 callback 视图。
     */
    public record RegisteredCallback(String subscriptionId, ChatCallback<AgentUiEventEnvelope> callback) {
    }
}
