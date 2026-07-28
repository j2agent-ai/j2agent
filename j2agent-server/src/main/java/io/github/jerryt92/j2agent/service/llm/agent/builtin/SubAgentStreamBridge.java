package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatCallback;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnLifecycle;
import io.github.jerryt92.j2agent.service.llm.queue.ChatOutputEventCache;
import io.github.jerryt92.j2agent.service.llm.tool.ToolEventEmitter;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用助手调用子智能体时，将子 Agent 流式增量桥接到父回合 WebSocket 缓冲区。
 */
public final class SubAgentStreamBridge {

    private static final Map<String, Target> BINDINGS = new ConcurrentHashMap<>();

    private SubAgentStreamBridge() {
    }

    public record Target(
            ChatCallback<AgentUiEventEnvelope> chatCallback,
            String contextId,
            String agentId,
            String turnId,
            String userId,
            String parentConversationId,
            ToolEventEmitter toolEventEmitter,
            ChatOutputEventCache outputEventCache,
            AtomicLong seq,
            AgentTurnStateMachine stateMachine,
            Object turnLock,
            StringBuilder streamedContent,
            StringBuilder streamedReasoning,
            Object streamedTextLock,
            int messageIndex) {

        public void emitDelta(String answerDelta, String reasoningDelta) {
            if (chatCallback == null) {
                appendStreamedDelta(answerDelta, reasoningDelta);
                saveSnapshot();
                return;
            }
            ChatTurnLifecycle.emitAnswerDelta(
                    chatCallback,
                    contextId,
                    turnId,
                    seq,
                    stateMachine,
                    turnLock,
                    streamedContent,
                    streamedReasoning,
                    streamedTextLock,
                    messageIndex,
                    answerDelta,
                    reasoningDelta);
            saveSnapshot();
        }

        private void appendStreamedDelta(String answerDelta, String reasoningDelta) {
            synchronized (streamedTextLock) {
                if (StringUtils.isNotBlank(answerDelta)) {
                    streamedContent.append(answerDelta);
                }
                if (StringUtils.isNotBlank(reasoningDelta)) {
                    streamedReasoning.append(reasoningDelta);
                }
            }
        }

        private void saveSnapshot() {
            if (outputEventCache == null) {
                return;
            }
            synchronized (streamedTextLock) {
                outputEventCache.saveSnapshot(
                        contextId,
                        agentId,
                        turnId,
                        streamedContent.toString(),
                        streamedReasoning.toString(),
                        stateMachine == null ? null : stateMachine.getState());
            }
        }
    }

    public static void bind(String turnId, Target target) {
        if (turnId != null && target != null) {
            BINDINGS.put(turnId, target);
        }
    }

    public static Target lookup(String turnId) {
        return turnId == null ? null : BINDINGS.get(turnId);
    }

    public static void unbind(String turnId) {
        if (turnId != null) {
            BINDINGS.remove(turnId);
        }
    }
}
