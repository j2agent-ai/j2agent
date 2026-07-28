package io.github.jerryt92.j2agent.service.llm.queue;

import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.model.AgentStateTransition;
import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 中的运行中回答快照。
 *
 * <p>用于页面刷新或网络断开后的 resume：
 * 后端先 replay {@link #stateTrail}，再发送 {@link #answerContent} / {@link #reasoningContent}
 * 覆盖当前 assistant 气泡，后续继续接实时 delta。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatOutputSnapshot {
    /** 会话 ID。 */
    private String contextId;
    /** Agent ID。 */
    private String agentId;
    /** 当前运行中的真实 turn ID。 */
    private String turnId;
    /** 已生成的回答全文。 */
    private String answerContent;
    /** 已生成的思考全文。 */
    private String reasoningContent;
    /** 最近一次 Agent 状态。 */
    private AgentState state;
    /** 最近更新时间戳。 */
    private long updatedAt;
    /** 轻量状态轨迹，用于恢复前端步骤条。 */
    private List<StateTrailItem> stateTrail = new ArrayList<>();

    /**
     * 可重放的轻量状态事件。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StateTrailItem {
        /** 事件后的 Agent 状态。 */
        private AgentState state;
        /** 状态迁移信息，可为空。 */
        private AgentStateTransition transition;
        /** 事件阶段。 */
        private AgentEventPhase phase;
        /** 事件类型。 */
        private AgentEventType eventType;
        /** 非 MESSAGE 事件的轻量 payload；正文 delta 不放这里，避免 replay 重复追加。 */
        private Object payload;
        /** 原事件时间戳。 */
        private long ts;
    }
}
