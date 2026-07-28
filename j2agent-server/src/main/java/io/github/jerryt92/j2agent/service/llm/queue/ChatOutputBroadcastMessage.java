package io.github.jerryt92.j2agent.service.llm.queue;

import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis Pub/Sub 输出广播消息。
 *
 * <p>WebSocketSession 只能留在本 JVM，跨节点只广播可序列化的事件，再由各节点推给本机连接。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatOutputBroadcastMessage {
    /** 会话 ID。 */
    private String contextId;
    /** Agent ID。 */
    private String agentId;
    /** 要广播给观察连接的 Agent UI 事件；complete=true 时为空。 */
    private AgentUiEventEnvelope event;
    /** 是否只广播连接收尾，不携带业务事件。 */
    private boolean complete;
}
