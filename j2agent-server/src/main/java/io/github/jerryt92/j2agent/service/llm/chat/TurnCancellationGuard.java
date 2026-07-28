package io.github.jerryt92.j2agent.service.llm.chat;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 统一 turn 取消检查入口。
 *
 * <p>主链路、编排链路和子智能体链路都通过这里检查取消状态。
 * 它同时读取本机 {@link ChatTurnCancellationRegistry} 与 Redis cancelled key，
 * 避免 Pub/Sub 消息时序或跨节点调用导致任务继续消费 token。</p>
 */
@Service
public class TurnCancellationGuard {
    private final ChatTurnControlService turnControlService;

    public TurnCancellationGuard(ChatTurnControlService turnControlService) {
        this.turnControlService = turnControlService;
    }

    /**
     * 判断指定 turn 是否已被取消。
     */
    public boolean isCancelled(String turnId) {
        return StringUtils.isNotBlank(turnId)
                && (ChatTurnCancellationRegistry.isCancelled(turnId)
                || turnControlService.isTurnCancelled(turnId));
    }

    /**
     * 若 turn 已取消则抛出协作式中断异常，调用方应停止后续 LLM / tool / 状态处理。
     */
    public void throwIfCancelled(String turnId) {
        if (isCancelled(turnId)) {
            ChatTurnCancellationRegistry.cancel(turnId);
            throw new TurnCancelledException(turnId);
        }
    }
}
