package io.github.jerryt92.j2agent.service.llm.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.service.llm.queue.ChatCallbackRegistry;
import io.github.jerryt92.j2agent.service.llm.queue.ChatQueueProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天 turn 控制面。
 *
 * <p>该服务把运行中的 {@code contextId + agentId} 映射到真实 {@code turnId}，
 * 并在本机保存该 turn 对应的取消句柄。主动停止时，任意节点都可以通过 Redis active key
 * 找到 turnId，再通过 Redis Pub/Sub 广播取消；运行节点收到消息后立即 dispose 主流/子流。</p>
 *
 * <p>它解决的是“后台任务可寻址取消”问题，不负责 WebSocket 推送；
 * UI 终态由 {@link io.github.jerryt92.j2agent.service.llm.queue.ChatOutputDispatcher} 负责广播。</p>
 */
@Slf4j
@Service
public class ChatTurnControlService {
    private static final String ACTIVE_TURN_PREFIX = "chat:turn:active:";
    private static final String CANCELLED_TURN_PREFIX = "chat:turn:cancelled:";
    private static final String TURN_CANCEL_TOPIC_KEY = "chat:turn:cancel";

    private final RedissonClient redissonClient;
    private final RedisKeyNamespaces redisKeyNamespaces;
    private final ChatQueueProperties properties;
    private final TypedJsonJacksonCodec cancelCodec;
    /** 本机运行中的 turn 句柄：key = turnId。 */
    private final ConcurrentMap<String, LocalTurnHandle> localTurns = new ConcurrentHashMap<>();
    private Integer cancelListenerId;

    public ChatTurnControlService(RedissonClient redissonClient,
                                  RedisKeyNamespaces redisKeyNamespaces,
                                  ChatQueueProperties properties,
                                  ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.redisKeyNamespaces = redisKeyNamespaces;
        this.properties = properties;
        this.cancelCodec = new TypedJsonJacksonCodec(ChatTurnCancelMessage.class, objectMapper);
    }

    /**
     * 订阅跨节点取消 topic。运行节点收到消息后只按 turnId 取消本机资源。
     */
    @PostConstruct
    public void subscribeTurnCancelTopic() {
        RTopic topic = turnCancelTopic();
        cancelListenerId = topic.addListener(ChatTurnCancelMessage.class,
                (MessageListener<ChatTurnCancelMessage>) (channel, message) -> {
                    if (message == null || !StringUtils.isNotBlank(message.getTurnId())) {
                        return;
                    }
                    cancelTurnLocal(message.getTurnId(), message.getReason());
                });
        log.info("Subscribed chat turn cancel topic: {}", turnCancelTopicName());
    }

    @PreDestroy
    public void unsubscribeTurnCancelTopic() {
        if (cancelListenerId != null) {
            turnCancelTopic().removeListener(cancelListenerId);
            cancelListenerId = null;
        }
    }

    /**
     * 注册一个正在执行的 turn。
     *
     * <p>同一真实 turn 可同时注册 raw agentId 与 resolved agentId，避免前端传 alias 时停不到。</p>
     */
    public void registerTurn(String contextId, String agentId, String turnId, Runnable onCancel) {
        if (!StringUtils.isNotBlank(contextId) || !StringUtils.isNotBlank(agentId) || !StringUtils.isNotBlank(turnId)) {
            return;
        }
        clearTurnCancelled(turnId);
        activeTurnBucket(contextId, agentId).set(turnId, activeTtl());
        localTurns.computeIfAbsent(turnId, ignored -> new LocalTurnHandle(onCancel))
                .setOnCancel(onCancel);
    }

    /**
     * 取消注册运行中 turn。仅当 Redis active key 仍指向当前 turnId 时才删除，避免误删新 turn。
     */
    public void unregisterTurn(String contextId, String agentId, String turnId) {
        if (StringUtils.isNotBlank(contextId) && StringUtils.isNotBlank(agentId)) {
            RBucket<String> bucket = activeTurnBucket(contextId, agentId);
            String activeTurnId = bucket.get();
            if (turnId != null && turnId.equals(activeTurnId)) {
                bucket.delete();
            }
        }
        if (StringUtils.isNotBlank(turnId)) {
            localTurns.remove(turnId);
        }
    }

    /**
     * 把 Reactor stream 的 Disposable 注册到 turn 下。
     *
     * <p>主 Agent 与委派子 Agent 都注册同一个父 turnId，因此一次主动停止能同时掐断父流和子流。</p>
     */
    public void registerDisposable(String turnId, Disposable disposable) {
        if (!StringUtils.isNotBlank(turnId) || disposable == null) {
            return;
        }
        ChatTurnCancellationRegistry.registerDisposable(turnId, disposable);
        localTurns.computeIfAbsent(turnId, ignored -> new LocalTurnHandle(null))
                .addDisposable(disposable);
        if (isTurnCancelled(turnId)) {
            cancelTurnLocal(turnId, "already cancelled");
        }
    }

    /**
     * 查询某个 session 当前真实运行中的 turnId。
     */
    public String getActiveTurnId(String contextId, String agentId) {
        if (!StringUtils.isNotBlank(contextId) || !StringUtils.isNotBlank(agentId)) {
            return null;
        }
        return activeTurnBucket(contextId, agentId).get();
    }

    /**
     * 按 session 取消当前 active turn。
     *
     * @return 命中的真实 turnId；若当前没有 running turn，则返回 null。
     */
    public String cancelSession(String contextId, String agentId, String reason) {
        String turnId = getActiveTurnId(contextId, agentId);
        if (!StringUtils.isNotBlank(turnId)) {
            return null;
        }
        cancelTurn(contextId, agentId, turnId, reason);
        return turnId;
    }

    /**
     * 按 turnId 发起取消：写 Redis cancelled key、发布跨节点消息，并立即执行本地兜底取消。
     */
    public void cancelTurn(String contextId, String agentId, String turnId, String reason) {
        if (!StringUtils.isNotBlank(turnId)) {
            return;
        }
        markTurnCancelled(turnId);
        ChatTurnCancelMessage message = new ChatTurnCancelMessage(contextId, agentId, turnId,
                StringUtils.defaultIfBlank(reason, "user interrupt"));
        try {
            turnCancelTopic().publish(message);
        } catch (Throwable t) {
            log.warn("Redis turn cancel publish failed, fallback to local cancel, turnId={}, error={}",
                    turnId, t.getMessage());
            cancelTurnLocal(turnId, reason);
        }
        cancelTurnLocal(turnId, reason);
    }

    /**
     * Redis cancelled key 是否存在。协作式检查通过它覆盖 Pub/Sub 时序问题。
     */
    public boolean isTurnCancelled(String turnId) {
        return StringUtils.isNotBlank(turnId) && cancelledTurnBucket(turnId).isExists();
    }

    /**
     * 新 turn 复用前清除同名 cancelled key，避免旧取消影响新任务。
     */
    public void clearTurnCancelled(String turnId) {
        if (StringUtils.isNotBlank(turnId)) {
            cancelledTurnBucket(turnId).delete();
        }
    }

    private void markTurnCancelled(String turnId) {
        cancelledTurnBucket(turnId).set("1", cancelTtl());
        ChatTurnCancellationRegistry.cancel(turnId);
    }

    private void cancelTurnLocal(String turnId, String reason) {
        ChatTurnCancellationRegistry.cancel(turnId);
        LocalTurnHandle handle = localTurns.get(turnId);
        if (handle != null) {
            handle.cancel(reason);
        }
    }

    private RBucket<String> activeTurnBucket(String contextId, String agentId) {
        return redissonClient.getBucket(
                redisKeyNamespaces.key(ACTIVE_TURN_PREFIX + ChatCallbackRegistry.sessionKey(contextId, agentId)),
                StringCodec.INSTANCE);
    }

    private RBucket<String> cancelledTurnBucket(String turnId) {
        return redissonClient.getBucket(
                redisKeyNamespaces.key(CANCELLED_TURN_PREFIX + turnId.trim()),
                StringCodec.INSTANCE);
    }

    private RTopic turnCancelTopic() {
        return redissonClient.getTopic(turnCancelTopicName(), cancelCodec);
    }

    private String turnCancelTopicName() {
        return redisKeyNamespaces.key(TURN_CANCEL_TOPIC_KEY);
    }

    private Duration activeTtl() {
        return Duration.ofSeconds(Math.max(3600, properties.getOutputCacheTtlSeconds()));
    }

    private Duration cancelTtl() {
        return Duration.ofSeconds(Math.max(60, properties.getQueuedTaskTtlSeconds()));
    }

    /**
     * 本机 turn 取消句柄，聚合主 Agent / 子 Agent 的 Disposable 与一次性 onCancel 回调。
     */
    private static final class LocalTurnHandle {
        private final CopyOnWriteArraySet<Disposable> disposables = new CopyOnWriteArraySet<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Runnable onCancel;

        private LocalTurnHandle(Runnable onCancel) {
            this.onCancel = onCancel;
        }

        private void setOnCancel(Runnable onCancel) {
            if (onCancel != null) {
                this.onCancel = onCancel;
            }
        }

        private void addDisposable(Disposable disposable) {
            if (cancelled.get()) {
                dispose(disposable);
                return;
            }
            disposables.add(disposable);
        }

        /**
         * 执行本机取消。compareAndSet 保证同一个 turn 的取消回调只触发一次。
         */
        private void cancel(String reason) {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            for (Disposable disposable : disposables) {
                dispose(disposable);
            }
            Runnable callback = onCancel;
            if (callback != null) {
                callback.run();
            }
        }

        private static void dispose(Disposable disposable) {
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
        }
    }
}
