package io.github.jerryt92.j2agent.service.llm.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatCallback;
import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.service.llm.AgentEventBuilder;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnControlService;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Map;

/**
 * 聊天 output 分发器。
 *
 * <p>正常 Agent 输出先发布到 Redis Pub/Sub，再由每个节点推给本机已注册的 WebSocket callback。
 * 这样同一用户多个客户端或多个浏览器窗口可以同时观察同一个 {@code contextId + agentId} 后台任务。</p>
 *
 * <p>注意：真实 WebSocketSession 仍只在本 JVM 内调用；Redis 中只传播可序列化的事件。</p>
 */
@Slf4j
@Service
public class ChatOutputDispatcher {
    private static final String OUTPUT_TOPIC_KEY = "chat:output:events";

    private final ChatCallbackRegistry callbackRegistry;
    private final ChatOutputEventCache outputEventCache;
    private final RedissonClient redissonClient;
    private final RedisKeyNamespaces redisKeyNamespaces;
    private final ChatTurnControlService chatTurnControlService;
    private final TypedJsonJacksonCodec broadcastCodec;
    private final ConcurrentMap<String, Object> sendLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> latestTurnIds = new ConcurrentHashMap<>();
    private Integer topicListenerId;

    public ChatOutputDispatcher(ChatCallbackRegistry callbackRegistry,
                                ChatOutputEventCache outputEventCache,
                                RedissonClient redissonClient,
                                RedisKeyNamespaces redisKeyNamespaces,
                                ObjectMapper objectMapper,
                                ChatTurnControlService chatTurnControlService) {
        this.callbackRegistry = callbackRegistry;
        this.outputEventCache = outputEventCache;
        this.redissonClient = redissonClient;
        this.redisKeyNamespaces = redisKeyNamespaces;
        this.chatTurnControlService = chatTurnControlService;
        this.broadcastCodec = new TypedJsonJacksonCodec(ChatOutputBroadcastMessage.class, objectMapper);
    }

    /**
     * 订阅跨节点 output topic。收到事件后只写本机 callback，不跨 JVM 调用 WebSocket。
     */
    @PostConstruct
    public void subscribeOutputTopic() {
        RTopic topic = outputTopic();
        topicListenerId = topic.addListener(ChatOutputBroadcastMessage.class,
                (MessageListener<ChatOutputBroadcastMessage>) (channel, message) -> {
                    if (message == null || message.getContextId() == null || message.getAgentId() == null) {
                        return;
                    }
                    if (message.isComplete()) {
                        completeLocalSession(message.getContextId(), message.getAgentId());
                    } else if (message.getEvent() != null) {
                        rememberTurnId(message.getContextId(), message.getAgentId(), message.getEvent());
                        dispatchToLocalSession(message.getContextId(), message.getAgentId(), message.getEvent());
                    }
                });
        log.info("Subscribed chat output topic: {}", outputTopicName());
    }

    @PreDestroy
    public void unsubscribeOutputTopic() {
        if (topicListenerId != null) {
            outputTopic().removeListener(topicListenerId);
            topicListenerId = null;
        }
    }

    /**
     * 定向发送给某个 subscription。若该连接已经不存在，则退化为 session 级广播。
     */
    public void dispatch(String contextId,
                         String agentId,
                         String subscriptionId,
                         AgentUiEventEnvelope event) {
        outputEventCache.save(contextId, agentId, event);
        rememberTurnId(contextId, agentId, event);
        ChatCallback<AgentUiEventEnvelope> callback = callbackRegistry.get(contextId, agentId, subscriptionId);
        String targetSubscriptionId = subscriptionId;
        if (callback == null) {
            dispatchToSession(contextId, agentId, event);
            return;
        }
        send(contextId, agentId, targetSubscriptionId, callback, event);
    }

    /**
     * session 级广播：同一 {@code contextId + agentId} 下所有本机观察连接都会收到。
     */
    public void dispatchToSession(String contextId,
                                  String agentId,
                                  AgentUiEventEnvelope event) {
        outputEventCache.save(contextId, agentId, event);
        rememberTurnId(contextId, agentId, event);
        publish(new ChatOutputBroadcastMessage(contextId, agentId, event, false),
                () -> dispatchToLocalSession(contextId, agentId, event));
    }

    private void dispatchToLocalSession(String contextId,
                                        String agentId,
                                        AgentUiEventEnvelope event) {
        for (ChatCallbackRegistry.RegisteredCallback registered : callbackRegistry.listLocalCallbacks(contextId, agentId)) {
            send(contextId, agentId, registered.subscriptionId(), registered.callback(), event);
        }
    }

    /**
     * 串行写单个 WebSocket callback，避免同一 session 并发 sendMessage 乱序。
     */
    private void send(String contextId,
                      String agentId,
                      String subscriptionId,
                      ChatCallback<AgentUiEventEnvelope> callback,
                      AgentUiEventEnvelope event) {
        if (callback == null || callback.responseCall == null) {
            return;
        }
        Object lock = sendLocks.computeIfAbsent(
                ChatCallbackRegistry.key(contextId, agentId, subscriptionId), ignored -> new Object());
        synchronized (lock) {
            callback.responseCall.accept(event);
        }
    }

    /**
     * 关闭某个 session 的全部观察连接。
     */
    public void completeSession(String contextId, String agentId) {
        publish(new ChatOutputBroadcastMessage(contextId, agentId, null, true),
                () -> completeLocalSession(contextId, agentId));
    }

    private void completeLocalSession(String contextId, String agentId) {
        for (ChatCallbackRegistry.RegisteredCallback registered : callbackRegistry.listLocalCallbacks(contextId, agentId)) {
            complete(contextId, agentId, registered.subscriptionId());
        }
    }

    public void complete(String contextId, String agentId, String subscriptionId) {
        ChatCallback<AgentUiEventEnvelope> callback = callbackRegistry.get(contextId, agentId, subscriptionId);
        String targetSubscriptionId = subscriptionId;
        if (callback == null) {
            return;
        }
        try {
            if (callback != null && callback.completeCall != null) {
                callback.completeCall.run();
            }
        } finally {
            cleanup(contextId, agentId, targetSubscriptionId == null ? subscriptionId : targetSubscriptionId);
        }
    }

    /**
     * 发送整轮失败事件，并关闭当前 subscription。
     */
    public void fail(String contextId,
                     String agentId,
                     String subscriptionId,
                     String errorCode,
                     Throwable error) {
        AgentTurnStateMachine stateMachine = new AgentTurnStateMachine();
        AgentUiEventEnvelope envelope = AgentEventBuilder.buildTurnFailure(
                contextId,
                UUIDv7Utils.randomUUIDv7(),
                0L,
                stateMachine,
                errorCode,
                error);
        dispatch(contextId, agentId, subscriptionId, envelope);
        complete(contextId, agentId, subscriptionId);
    }

    public void cancelSession(String contextId, String agentId) {
        cancelSession(contextId, agentId, null);
    }

    /**
     * 广播取消终态。优先复用真实 running turnId，避免前端渲染出孤立 Cancelled 气泡。
     */
    public void cancelSession(String contextId, String agentId, String preferredTurnId) {
        String turnId = StringUtils.isNotBlank(preferredTurnId)
                ? preferredTurnId
                : resolveLatestTurnId(contextId, agentId);
        AgentUiEventEnvelope envelope = AgentEventBuilder.build(
                contextId,
                StringUtils.isNotBlank(turnId) ? turnId : UUIDv7Utils.randomUUIDv7(),
                0L,
                AgentState.CANCELLED,
                null,
                AgentEventPhase.COMPLETE,
                AgentEventType.SYSTEM,
                Map.of("notice", "cancelled"));
        dispatchToSession(contextId, agentId, envelope);
        completeSession(contextId, agentId);
    }

    private void cleanup(String contextId, String agentId, String subscriptionId) {
        String key = ChatCallbackRegistry.key(contextId, agentId, subscriptionId);
        callbackRegistry.unregister(contextId, agentId, subscriptionId);
        sendLocks.remove(key);
    }

    /**
     * 记录最近一次真实 turnId，active key 已清理时可作为取消事件兜底。
     */
    private void rememberTurnId(String contextId, String agentId, AgentUiEventEnvelope event) {
        if (event == null || !StringUtils.isNotBlank(event.getTurnId())) {
            return;
        }
        latestTurnIds.put(ChatCallbackRegistry.sessionKey(contextId, agentId), event.getTurnId());
    }

    /**
     * 取消事件使用的 turnId 解析顺序：active turn -> 最近输出 turn -> snapshot turn。
     */
    private String resolveLatestTurnId(String contextId, String agentId) {
        String activeTurnId = chatTurnControlService.getActiveTurnId(contextId, agentId);
        if (StringUtils.isNotBlank(activeTurnId)) {
            return activeTurnId;
        }
        String turnId = latestTurnIds.get(ChatCallbackRegistry.sessionKey(contextId, agentId));
        if (StringUtils.isNotBlank(turnId)) {
            return turnId;
        }
        ChatOutputSnapshot snapshot = outputEventCache.getSnapshot(contextId, agentId);
        return snapshot == null ? null : snapshot.getTurnId();
    }

    private void publish(ChatOutputBroadcastMessage message, Runnable localFallback) {
        try {
            outputTopic().publish(message);
        } catch (Throwable t) {
            log.warn("Redis output pub/sub publish failed, fallback to local dispatch: {}", t.getMessage());
            localFallback.run();
        }
    }

    private RTopic outputTopic() {
        return redissonClient.getTopic(outputTopicName(), broadcastCodec);
    }

    private String outputTopicName() {
        return redisKeyNamespaces.key(OUTPUT_TOPIC_KEY);
    }
}
