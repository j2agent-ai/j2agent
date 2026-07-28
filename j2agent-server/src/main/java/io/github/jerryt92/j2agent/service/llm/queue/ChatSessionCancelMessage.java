package io.github.jerryt92.j2agent.service.llm.queue;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis Pub/Sub 会话级取消消息。
 *
 * <p>用于通知各节点触发本机 session close handler。真正的 turn 级取消由
 * {@link io.github.jerryt92.j2agent.service.llm.chat.ChatTurnControlService} 负责。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionCancelMessage {
    /** 会话 ID。 */
    private String contextId;
    /** Agent ID。 */
    private String agentId;
    /** 取消原因。 */
    private String reason;
}
