package io.github.jerryt92.j2agent.service.llm.agent.builtin.universalagent;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.ThinkingOverrideRegistry;
import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamOptions;
import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamSession;
import io.github.jerryt92.j2agent.service.llm.agent.builtin.SubAgentStreamBridge;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunContext;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import io.github.jerryt92.j2agent.service.llm.chat.TurnCancelledException;
import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import io.github.jerryt92.j2agent.service.llm.rag.TurnRagSourceRegistry;
import io.github.jerryt92.j2agent.service.question.TurnAskQuestionRegistry;
import io.github.jerryt92.j2agent.service.llm.tool.ToolEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用助手子智能体调用（无 {@code @Tool}），由编排服务直接调用。
 * 委派调用使用 {@code subAgentCallRun=true}，不读写子智能体会话记忆，避免聊天记录落入专业 Agent 键。
 */
@Slf4j
@Service
public class UniversalSubAgentCallService {

    private static final int MAX_SUB_AGENT_CALL_RESULT_LENGTH = ToolEventEmitter.MAX_TOOL_RESULT_LENGTH;

    private final AgentRouter agentRouter;
    private final AgentStreamSession agentStreamSession;

    public UniversalSubAgentCallService(AgentRouter agentRouter, AgentStreamSession agentStreamSession) {
        this.agentRouter = agentRouter;
        this.agentStreamSession = agentStreamSession;
    }

    public String call(String agentId, String query, SubAgentCallRequest request) {
        if (StringUtils.isBlank(agentId)) {
            return "Error: agentId is required";
        }
        if (StringUtils.isBlank(query)) {
            return "Error: query is required";
        }
        if (request == null) {
            return "Error: missing turn context for sub-agent call";
        }
        String turnId = request.turnId();
        if (ChatTurnCancellationRegistry.isCancelled(turnId)) {
            throw new TurnCancelledException(turnId);
        }
        String trimmedAgentId = agentId.trim();
        String trimmedQuery = query.trim();
        boolean callable = agentRouter.listCallableSubAgents().stream()
                .anyMatch(a -> trimmedAgentId.equals(a.getAgentId()));
        if (!callable) {
            return "Error: unknown or non-callable agentId: " + trimmedAgentId;
        }

        AiAgent targetAgent;
        try {
            targetAgent = agentRouter.route(trimmedAgentId);
        } catch (Exception ex) {
            return "Error: failed to route agent: " + ex.getMessage();
        }

        String specialistConversationId = ConversationIdCodec.format(
                request.userId(), request.contextId(), trimmedAgentId);
        AgentThinkingOverride runtimeThinking = targetAgent.getThinkingOverride();
        ThinkingOverrideRegistry.bind(specialistConversationId, runtimeThinking);
        if (!TurnRagSourceRegistry.shareHolder(specialistConversationId, request.parentConversationId())) {
            AgentRunLogger.warnByConversationId(request.parentConversationId(), AgentRunEventType.RAG_SOURCE,
                    AgentRunLogger.kv("rag", "skipped=shareHolderFailed,specialist=" + specialistConversationId
                            + ",agentId=" + trimmedAgentId),
                    "sub-agent RAG source bridge failed");
        }
        TurnAskQuestionRegistry.shareHolder(specialistConversationId, request.parentConversationId());

        SubAgentStreamBridge.Target bridge = SubAgentStreamBridge.lookup(turnId);
        StringBuilder content = bridge != null ? bridge.streamedContent() : new StringBuilder();
        StringBuilder reasoning = bridge != null ? bridge.streamedReasoning() : new StringBuilder();
        Object textLock = bridge != null ? bridge.streamedTextLock() : new Object();
        Object subAgentTurnLock = new Object();
        AgentTurnStateMachine subAgentStateMachine = new AgentTurnStateMachine();

        List<ChatAttachmentDto> attachments = request.attachments() == null ? List.of() : request.attachments();

        AgentRunContext subContext = new AgentRunContext(
                trimmedQuery,
                request.contextId(),
                request.userId(),
                turnId,
                specialistConversationId,
                trimmedAgentId,
                request.userContext(),
                attachments,
                List.of(),
                request.toolEventEmitter(),
                true,
                false);

        try {
            streamSubAgentToCompletion(targetAgent, subContext, turnId, bridge, content, reasoning, textLock,
                    subAgentTurnLock, subAgentStateMachine);

            if (ChatTurnCancellationRegistry.isCancelled(turnId)) {
                throw new TurnCancelledException(turnId);
            }

            String text = content.toString().trim();
            if (StringUtils.isBlank(text)) {
                log.warn("sub-agent call empty result: agentId={}", trimmedAgentId);
                return "子智能体未返回有效内容，请稍后重试。";
            }
            if (MAX_SUB_AGENT_CALL_RESULT_LENGTH > 0 && text.length() > MAX_SUB_AGENT_CALL_RESULT_LENGTH) {
                return text.substring(0, MAX_SUB_AGENT_CALL_RESULT_LENGTH);
            }
            return text;
        } catch (TurnCancelledException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("sub-agent call 失败: agentId={}", trimmedAgentId, ex);
            Throwable cause = ex instanceof RuntimeException runtime && runtime.getCause() != null
                    ? runtime.getCause()
                    : ex;
            if (cause instanceof GraphRunnerException) {
                return "调用子智能体时出现问题，请稍后重试或换一种方式描述您的问题。";
            }
            return "调用子智能体时出现问题: " + ex.getMessage();
        } finally {
            ThinkingOverrideRegistry.unbind(specialistConversationId);
            TurnAskQuestionRegistry.unshareHolder(specialistConversationId);
        }
    }

    private void streamSubAgentToCompletion(AiAgent targetAgent,
                                            AgentRunContext subContext,
                                            String turnId,
                                            SubAgentStreamBridge.Target bridge,
                                            StringBuilder content,
                                            StringBuilder reasoning,
                                            Object textLock,
                                            Object subAgentTurnLock,
                                            AgentTurnStateMachine subAgentStateMachine) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        Disposable disposable = agentStreamSession.stream(new AgentStreamOptions(
                        targetAgent,
                        subContext,
                        null,
                        subAgentStateMachine,
                        content,
                        reasoning,
                        textLock,
                        subAgentTurnLock,
                        new AtomicLong(0),
                        new AtomicInteger(0),
                        null))
                .takeWhile(parts -> !ChatTurnCancellationRegistry.isCancelled(turnId))
                .doOnNext(parts -> {
                    if (bridge != null) {
                        bridge.emitDelta(parts.answerDelta(), parts.reasoningDelta());
                        return;
                    }
                    if (StringUtils.isNotBlank(parts.answerDelta())) {
                        synchronized (textLock) {
                            content.append(parts.answerDelta());
                        }
                    }
                    if (StringUtils.isNotBlank(parts.reasoningDelta())) {
                        synchronized (textLock) {
                            reasoning.append(parts.reasoningDelta());
                        }
                    }
                })
                .doOnError(errorRef::set)
                .doFinally(signal -> latch.countDown())
                .subscribe();
        ChatTurnCancellationRegistry.registerDisposable(turnId, disposable);
        try {
            latch.await();
        } catch (InterruptedException ex) {
            disposable.dispose();
            Thread.currentThread().interrupt();
            throw ex;
        }
        Throwable error = errorRef.get();
        if (error != null) {
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(error);
        }
    }

    public record SubAgentCallRequest(
            String contextId,
            String turnId,
            String userId,
            String parentConversationId,
            ToolEventEmitter toolEventEmitter,
            List<ChatAttachmentDto> attachments,
            UserContextBo userContext) {
    }
}
