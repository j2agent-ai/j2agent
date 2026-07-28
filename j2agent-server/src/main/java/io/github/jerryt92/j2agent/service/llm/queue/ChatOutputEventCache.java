package io.github.jerryt92.j2agent.service.llm.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 运行中回答快照缓存。
 *
 * <p>Redis 中只保存当前回答全文、思考全文和轻量状态轨迹；
 * 不逐 token 保存事件，也不作为历史权威数据。页面刷新后先读取 snapshot 覆盖气泡，
 * 再继续接收后续 WebSocket 增量。</p>
 */
@Service
public class ChatOutputEventCache {
    private static final int MAX_STATE_TRAIL_SIZE = 64;

    private final RedissonClient redissonClient;
    private final RedisKeyNamespaces redisKeyNamespaces;
    private final ChatQueueProperties properties;
    private final TypedJsonJacksonCodec snapshotCodec;

    public ChatOutputEventCache(RedissonClient redissonClient,
                                RedisKeyNamespaces redisKeyNamespaces,
                                ChatQueueProperties properties,
                                ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.redisKeyNamespaces = redisKeyNamespaces;
        this.properties = properties;
        this.snapshotCodec = new TypedJsonJacksonCodec(ChatOutputSnapshot.class, objectMapper);
    }

    /**
     * 保留旧接口。当前简化方案不逐条缓存 output 事件，正文由 saveSnapshot 刷新。
     */
    public void save(String contextId, String agentId, AgentUiEventEnvelope event) {
        // 简化版重连补发只缓存全文快照，复杂事件不逐条缓存。
    }

    /**
     * 保存当前运行中回答全文快照。
     *
     * <p>每次 answer/reasoning delta 后传入累计全文，Redis 中始终是“到目前为止的完整正文”。</p>
     */
    public void saveSnapshot(String contextId,
                             String agentId,
                             String turnId,
                             String answerContent,
                             String reasoningContent,
                             AgentState state) {
        if (!properties.isOutputCacheEnabled() || !StringUtils.isNotBlank(turnId)) {
            return;
        }
        ChatOutputSnapshot current = snapshotBucket(contextId, agentId).get();
        List<ChatOutputSnapshot.StateTrailItem> stateTrail = current == null
                ? new ArrayList<>()
                : new ArrayList<>(current.getStateTrail() == null ? List.of() : current.getStateTrail());
        snapshotBucket(contextId, agentId).set(
                new ChatOutputSnapshot(
                        contextId,
                        agentId,
                        turnId,
                        answerContent == null ? "" : answerContent,
                        reasoningContent == null ? "" : reasoningContent,
                        state,
                        System.currentTimeMillis(),
                        stateTrail),
                Duration.ofSeconds(properties.getOutputCacheTtlSeconds()));
    }

    /**
     * 保存轻量状态轨迹，供刷新后恢复工具调用、编排、取消等 UI 步骤。
     */
    public void saveStateTrailEvent(String contextId, String agentId, AgentUiEventEnvelope event) {
        if (!properties.isOutputCacheEnabled()
                || event == null
                || !StringUtils.isNotBlank(event.getTurnId())
                || event.getState() == null) {
            return;
        }
        RBucket<ChatOutputSnapshot> bucket = snapshotBucket(contextId, agentId);
        ChatOutputSnapshot snapshot = bucket.get();
        if (snapshot == null || !event.getTurnId().equals(snapshot.getTurnId())) {
            snapshot = new ChatOutputSnapshot(
                    contextId,
                    agentId,
                    event.getTurnId(),
                    "",
                    "",
                    event.getState(),
                    System.currentTimeMillis(),
                    new ArrayList<>());
        }
        List<ChatOutputSnapshot.StateTrailItem> trail = snapshot.getStateTrail();
        if (trail == null) {
            trail = new ArrayList<>();
            snapshot.setStateTrail(trail);
        }
        Object payload = event.getEventType() == io.github.jerryt92.j2agent.model.AgentEventType.MESSAGE
                ? null
                : event.getPayload();
        ChatOutputSnapshot.StateTrailItem item = new ChatOutputSnapshot.StateTrailItem(
                event.getState(),
                event.getTransition(),
                event.getPhase(),
                event.getEventType(),
                payload,
                event.getTs() == null ? System.currentTimeMillis() : event.getTs());
        if (isDuplicateTrailTail(trail, item)) {
            snapshot.setState(event.getState());
            snapshot.setUpdatedAt(System.currentTimeMillis());
            bucket.set(snapshot, Duration.ofSeconds(properties.getOutputCacheTtlSeconds()));
            return;
        }
        trail.add(item);
        if (trail.size() > MAX_STATE_TRAIL_SIZE) {
            trail.subList(0, trail.size() - MAX_STATE_TRAIL_SIZE).clear();
        }
        snapshot.setState(event.getState());
        snapshot.setUpdatedAt(System.currentTimeMillis());
        bucket.set(snapshot, Duration.ofSeconds(properties.getOutputCacheTtlSeconds()));
    }

    /**
     * 读取运行中 snapshot；output cache 关闭时固定返回 null。
     */
    public ChatOutputSnapshot getSnapshot(String contextId, String agentId) {
        if (!properties.isOutputCacheEnabled()) {
            return null;
        }
        return snapshotBucket(contextId, agentId).get();
    }

    /**
     * 回答进入终态后清理临时 snapshot，最终内容由历史记录接口负责。
     */
    public void clearSnapshot(String contextId, String agentId) {
        if (!StringUtils.isNotBlank(contextId) || !StringUtils.isNotBlank(agentId)) {
            return;
        }
        snapshotBucket(contextId, agentId).delete();
    }

    private RBucket<ChatOutputSnapshot> snapshotBucket(String contextId, String agentId) {
        return redissonClient.getBucket(
                redisKeyNamespaces.key("chat:output:snapshot:" + ChatCallbackRegistry.sessionKey(contextId, agentId)),
                snapshotCodec);
    }

    /**
     * 去重连续重复状态，避免 replay 后前端出现重复步骤。
     */
    private boolean isDuplicateTrailTail(List<ChatOutputSnapshot.StateTrailItem> trail,
                                         ChatOutputSnapshot.StateTrailItem item) {
        if (trail.isEmpty()) {
            return false;
        }
        ChatOutputSnapshot.StateTrailItem last = trail.get(trail.size() - 1);
        return last.getState() == item.getState()
                && Objects.equals(last.getTransition(), item.getTransition())
                && last.getPhase() == item.getPhase()
                && last.getEventType() == item.getEventType()
                && Objects.equals(last.getPayload(), item.getPayload());
    }
}
