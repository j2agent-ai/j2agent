package io.github.jerryt92.j2agent.service.llm.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * turn 级取消广播消息。
 *
 * <p>stop API 可以打到任意节点；真正运行 LLM 的节点通过 Redis Pub/Sub 收到该消息后，
 * 按 turnId dispose 主 Agent / 子 Agent 的流式订阅。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatTurnCancelMessage {
    /** 会话 ID，用于日志和辅助定位。 */
    private String contextId;
    /** Agent ID，用于日志和辅助定位。 */
    private String agentId;
    /** 真实运行中的 turn ID，是取消的权威寻址键。 */
    private String turnId;
    /** 取消原因，如 user interrupt。 */
    private String reason;
}
