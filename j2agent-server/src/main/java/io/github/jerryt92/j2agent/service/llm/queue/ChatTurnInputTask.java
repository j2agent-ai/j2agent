package io.github.jerryt92.j2agent.service.llm.queue;

import io.github.jerryt92.j2agent.model.ChatRequestDto;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一轮聊天输入任务。
 *
 * <p>该对象由 WebSocket 入口构造后写入 Redis 阻塞队列。worker 消费时，
 * 按 {@code contextId + agentId} 串行调用 {@code ChatService}，避免同一会话并发写记忆或交叉输出。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatTurnInputTask {
    /** 会话 ID，对应历史/记忆维度。 */
    private String contextId;
    /** Agent ID，对应路由和会话串行维度。 */
    private String agentId;
    /** WebSocket 连接 ID，只用于区分同一会话下的不同观察连接。 */
    private String subscriptionId;
    /** 前端输入对应的 turn ID；真正执行时 ChatService 仍以运行态 turn 为准。 */
    private String turnId;
    /** UI 发送的原始请求体。 */
    private ChatRequestDto request;
    /** 握手阶段解析出的用户上下文，worker 后台线程继续沿用。 */
    private UserContextBo userContext;
    /** 入队时间戳，用于后续排队耗时统计或过期策略扩展。 */
    private long enqueueTimeMs;
}
