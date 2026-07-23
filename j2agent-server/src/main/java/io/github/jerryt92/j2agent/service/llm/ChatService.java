package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamOptions;
import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamSession;
import io.github.jerryt92.j2agent.config.chat.ActiveChatTurnProperties;
import io.github.jerryt92.j2agent.config.chat.ChatInputProperties;
import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.model.AgentStateTransition;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.ChatCallback;
import io.github.jerryt92.j2agent.model.ChatRequestDto;
import io.github.jerryt92.j2agent.model.ChatResponseDto;
import io.github.jerryt92.j2agent.model.MessageDto;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunContext;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import io.github.jerryt92.j2agent.service.llm.reasoning.SpringAiReasoningMetadataAdapter;
import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogContext;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogSnapshot;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import io.github.jerryt92.j2agent.service.llm.rag.TurnRagSourceRegistry;
import io.github.jerryt92.j2agent.service.question.TurnAskQuestionRegistry;
import io.github.jerryt92.j2agent.service.llm.tool.ToolEventEmitter;
import io.github.jerryt92.j2agent.service.llm.usage.TurnUsageAccumulator;
import io.github.jerryt92.j2agent.service.llm.usage.TurnUsageContext;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnLifecycle;
import io.github.jerryt92.j2agent.service.llm.chat.TurnCancelledException;
import io.github.jerryt92.j2agent.service.llm.agent.builtin.SubAgentStreamBridge;
import io.github.jerryt92.j2agent.service.llm.agent.builtin.UniversalAssistantOrchestratorService;
import io.github.jerryt92.j2agent.service.llm.universal.UniversalAssistantConstants;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Service
public class ChatService {
    private static final String ANONYMOUS_USER = "anonymous";
    private final ChatContextService chatContextService;
    private final ChatInputProperties chatInputProperties;
    private final ActiveChatTurnProperties activeChatTurnProperties;
    private final ActiveChatTurnRegistry activeChatTurnRegistry;
    static Map<String, ChatCallback<AgentUiEventEnvelope>> contextChatCallbackMap = new HashMap<>();
    private final AgentRouter agentRouter;
    private final ChatMemoryMessageCodec chatMemoryMessageCodec;
    private final ObjectProvider<io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentService>
            chatAttachmentServiceProvider;
    private final AgentStreamSession agentStreamSession;
    private final UniversalAssistantOrchestratorService universalAssistantOrchestratorService;

    public ChatService(ChatContextService chatContextService,
                       AgentRouter agentRouter,
                       ChatInputProperties chatInputProperties,
                       ActiveChatTurnProperties activeChatTurnProperties,
                       ChatMemoryMessageCodec chatMemoryMessageCodec,
                       ActiveChatTurnRegistry activeChatTurnRegistry,
                       ObjectProvider<io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentService>
                               chatAttachmentServiceProvider,
                       AgentStreamSession agentStreamSession,
                       UniversalAssistantOrchestratorService universalAssistantOrchestratorService) {
        this.chatContextService = chatContextService;
        this.agentRouter = agentRouter;
        this.chatInputProperties = chatInputProperties;
        this.activeChatTurnProperties = activeChatTurnProperties;
        this.chatMemoryMessageCodec = chatMemoryMessageCodec;
        this.activeChatTurnRegistry = activeChatTurnRegistry;
        this.chatAttachmentServiceProvider = chatAttachmentServiceProvider;
        this.agentStreamSession = agentStreamSession;
        this.universalAssistantOrchestratorService = universalAssistantOrchestratorService;
    }

    public static void registerContextChatCallback(String contextId, ChatCallback<AgentUiEventEnvelope> callback) {
        contextChatCallbackMap.put(contextId, callback);
    }

    public static void unregisterContextChatCallback(String contextId) {
        contextChatCallbackMap.remove(contextId);
    }

    public void handleChat(
            ChatCallback<AgentUiEventEnvelope> chatChatCallback,
            ChatRequestDto request,
            UserContextBo userContext,
            String agentId) {
        String userId = userContext == null ? null : userContext.getUserId();
        String turnId = UUIDv7Utils.randomUUIDv7();
        ChatTurnCancellationRegistry.clear(turnId);
        AtomicLong seq = new AtomicLong(0L);
        AgentTurnStateMachine stateMachine = new AgentTurnStateMachine();
        Object turnLock = new Object();
        AtomicBoolean terminated = new AtomicBoolean(false);
        TurnStepRecorder turnStepRecorder = new TurnStepRecorder();
        AtomicBoolean turnTraceFlushed = new AtomicBoolean(false);
        Runnable originalCompleteCall = chatChatCallback.completeCall;
        String resolvedContextId = request.getContextId();
        if (resolvedContextId == null) {
            resolvedContextId = UUIDv7Utils.randomUUIDv7();
        } else if (userId != null && !chatContextService.userOwnsContext(resolvedContextId, userId)) {
            throw new IllegalArgumentException("Current user does not own the contextId.");
        }
        final String contextId = resolvedContextId;
        final AtomicReference<String> conversationIdRef = new AtomicReference<>(null);
        final AtomicReference<ChatMemory> agentChatMemoryRef = new AtomicReference<>(null);
        Consumer<AgentUiEventEnvelope> originalResponseCall = chatChatCallback.responseCall;
        Runnable flushTurnTrace = () -> flushTurnTrace(
                agentChatMemoryRef.get(),
                conversationIdRef.get(),
                turnId,
                turnStepRecorder,
                turnTraceFlushed);
        final AtomicReference<String> resolvedAgentIdRef = new AtomicReference<>(null);
        try {
            contextChatCallbackMap.put(contextId, chatChatCallback);
            if (CollectionUtils.isEmpty(request.getMessages())) {
                terminateTurnWithFailure(chatChatCallback, contextId, turnId, seq, stateMachine, turnLock,
                        terminated, originalCompleteCall, "emptyMessages", null, null, flushTurnTrace,
                        () -> contextChatCallbackMap.remove(contextId));
                return;
            }
            final AiAgent aiAgentForConversation = agentRouter.route(agentId);
            final String resolvedAgentId = aiAgentForConversation.getAgentId();
            resolvedAgentIdRef.set(resolvedAgentId);
            activeChatTurnRegistry.register(contextId, resolvedAgentId);
            AtomicLong lastHeartbeatTouchMs = new AtomicLong(0L);
            int heartbeatTouchIntervalMs = activeChatTurnProperties.getHeartbeatTouchIntervalSeconds() * 1000;
            Consumer<AgentUiEventEnvelope> recordingResponseCall = envelope -> {
                if (ChatTurnCancellationRegistry.isCancelled(turnId)) {
                    turnStepRecorder.record(envelope);
                    return;
                }
                turnStepRecorder.record(envelope);
                long now = System.currentTimeMillis();
                long last = lastHeartbeatTouchMs.get();
                if (now - last >= heartbeatTouchIntervalMs && lastHeartbeatTouchMs.compareAndSet(last, now)) {
                    activeChatTurnRegistry.touch(contextId, resolvedAgentId);
                }
                if (originalResponseCall != null) {
                    originalResponseCall.accept(envelope);
                }
            };
            chatChatCallback.responseCall = recordingResponseCall;
            AtomicBoolean activeTurnReleased = new AtomicBoolean(false);
            Runnable releaseActiveTurn = () -> {
                if (activeTurnReleased.compareAndSet(false, true)) {
                    String conversationId = conversationIdRef.get();
                    if (conversationId != null) {
                        AgentRunLogContext.clear(conversationId);
                    }
                    activeChatTurnRegistry.unregister(contextId, resolvedAgentId);
                    contextChatCallbackMap.remove(contextId);
                }
            };
            final String turnConversationId = buildConversationId(userId, contextId, resolvedAgentId);
            StreamedAssistantPersistence.enable(turnConversationId);
            TurnUsageAccumulator.bind(new TurnUsageContext(
                    contextId,
                    resolvedAgentId,
                    turnId,
                    turnConversationId,
                    userId));
            conversationIdRef.set(turnConversationId);
            final AgentRunLogSnapshot runLogSnapshot = new AgentRunLogSnapshot(
                    contextId,
                    turnId,
                    turnConversationId,
                    resolveLogUserId(userId),
                    resolvedAgentId);
            AgentRunLogContext.bind(turnConversationId, runLogSnapshot);
            int index = request.getMessages().size() - 1;
            TurnRagSourceRegistry.bind(turnConversationId, recordingResponseCall, turnLock, contextId, turnId, seq,
                    stateMachine, index);
            TurnAskQuestionRegistry.bind(turnConversationId, recordingResponseCall, turnLock, contextId, turnId,
                    seq, stateMachine, index);
            StringBuilder streamedContent = new StringBuilder();
            StringBuilder streamedReasoning = new StringBuilder();
            Object streamedTextLock = new Object();
            /** 正常结束或中断时，以 streamedContent/streamedReasoning 落库 assistant 一次 */
            AtomicBoolean streamedAssistantFlushed = new AtomicBoolean(false);
            ToolEventEmitter toolEventEmitter = new ToolEventEmitter(
                    contextId,
                    turnId,
                    seq,
                    stateMachine,
                    turnLock,
                    recordingResponseCall);
            String latestUserMessage = extractLatestUserMessage(request.getMessages());
            String limitedUserMessage = limitMessageLength(latestUserMessage);
            MessageDto latestUser = extractLatestUser(request.getMessages());
            List<ChatAttachmentDto> attachments = List.of();
            if (!CollectionUtils.isEmpty(latestUser.getAttachments())) {
                var attachmentService = chatAttachmentServiceProvider.getIfAvailable();
                if (attachmentService == null) {
                    throw new IllegalStateException("Object storage is required for image input.");
                }
                attachments = attachmentService.validateAndReference(
                        latestUser.getAttachments(),
                        contextId,
                        resolvedAgentId,
                        latestUser.getIndex() == null ? index : latestUser.getIndex(),
                        userId);
            }
            if (StringUtils.isBlank(limitedUserMessage) && attachments.isEmpty()) {
                throw new IllegalArgumentException("User message and attachments must not both be empty.");
            }
            final List<ChatAttachmentDto> finalAttachments = attachments;
            final int userMessageIndex = latestUser.getIndex() == null ? index : latestUser.getIndex();
            if (UniversalAssistantConstants.isUniversalAssistant(resolvedAgentId)) {
                SubAgentStreamBridge.bind(turnId, new SubAgentStreamBridge.Target(
                        chatChatCallback,
                        contextId,
                        turnId,
                        userId,
                        turnConversationId,
                        toolEventEmitter,
                        seq,
                        stateMachine,
                        turnLock,
                        streamedContent,
                        streamedReasoning,
                        streamedTextLock,
                        userMessageIndex));
            }
            // 纯文本 assistant 由 streamedContent/streamedReasoning 落库；Advisor 仅写入 tool_calls 等结构化消息
            AiAgent aiAgent = aiAgentForConversation;
            AgentThinkingOverride effectiveThinking = ThinkingOverrideResolver.resolve(request, aiAgentForConversation);
            ThinkingOverrideRegistry.bind(turnConversationId, effectiveThinking);
            AtomicBoolean thinkingOverrideUnbound = new AtomicBoolean(false);
            Runnable unbindThinkingOverride = () -> {
                if (thinkingOverrideUnbound.compareAndSet(false, true)) {
                    ThinkingOverrideRegistry.unbind(turnConversationId);
                }
            };
            agentChatMemoryRef.set(aiAgentForConversation.getChatMemory());
            final ChatMemory agentChatMemory = agentChatMemoryRef.get();
            final boolean universalAssistant = UniversalAssistantConstants.isUniversalAssistant(resolvedAgentId);
            final List<String> knowledgeCollections = normalizeKnowledgeCollections(request.getKnowledgeCollections());
            if (UniversalAssistantConstants.isKnowledgeQaAssistant(resolvedAgentId)
                    && knowledgeCollections.isEmpty()) {
                throw new IllegalArgumentException("Knowledge collections are required.");
            }
            if (universalAssistant) {
                ChatTurnLifecycle.persistTurnUserMessage(
                        agentChatMemory, turnConversationId, limitedUserMessage, finalAttachments);
            }
            AgentRunContext agentRunContext = new AgentRunContext(
                    limitedUserMessage,
                    contextId,
                    userId,
                    turnId,
                    turnConversationId,
                    resolvedAgentId,
                    userContext,
                    finalAttachments,
                    knowledgeCollections,
                    toolEventEmitter,
                    false,
                    universalAssistant);
            AgentRunLogger.info(runLogSnapshot, AgentRunEventType.TURN_START,
                    AgentRunLogger.kv(
                            "userMsgLen", limitedUserMessage == null ? 0 : limitedUserMessage.length(),
                            "attachmentCount", finalAttachments.size()),
                    "turn started");
            AgentRunLogger.info(runLogSnapshot, AgentRunEventType.CHAT,
                    AgentRunLogger.kv(
                            "role", "user",
                            "msgLen", latestUserMessage == null ? 0 : latestUserMessage.length(),
                            "msgPreview", AgentRunLogger.preview(latestUserMessage)),
                    "user message accepted");
            AtomicLong streamStartedAtMs = new AtomicLong(0L);
            chatChatCallback.completeCall = () -> {
                if (!terminated.compareAndSet(false, true)) {
                    releaseActiveTurn.run();
                    return;
                }
                try {
                    synchronized (turnLock) {
                        if (stateMachine.getState() != AgentState.COMPLETED
                                && stateMachine.getState() != AgentState.FAILED
                                && stateMachine.getState() != AgentState.CANCELLED) {
                            AgentStateTransition toCompleted = stateMachine.transit(AgentState.COMPLETED, "streamFinished");
                            chatChatCallback.responseCall.accept(AgentEventBuilder.build(
                                    contextId,
                                    turnId,
                                    seq.getAndIncrement(),
                                    stateMachine.getState(),
                                    toCompleted,
                                    AgentEventPhase.COMPLETE,
                                    AgentEventType.SYSTEM,
                                    Map.of("notice", "completed")
                            ));
                        }
                    }
                } finally {
                    try {
                        persistStreamedAssistant(agentChatMemory, turnConversationId, streamedContent,
                                streamedReasoning, streamedTextLock, streamedAssistantFlushed, false);
                    } finally {
                        StreamedAssistantPersistence.disable(turnConversationId);
                        TurnUsageAccumulator.clear(turnConversationId);
                        TurnRagSourceRegistry.clear(turnConversationId);
                        TurnAskQuestionRegistry.clear(turnConversationId);
                        SubAgentStreamBridge.unbind(turnId);
                        ChatTurnCancellationRegistry.clear(turnId);
                        logTurnEnd(runLogSnapshot, streamStartedAtMs, turnStepRecorder, AgentState.COMPLETED);
                        flushTurnTrace.run();
                        runOriginalCompleteCall(originalCompleteCall);
                        releaseActiveTurn.run();
                    }
                }
            };
            chatChatCallback.errorCall = t -> {
                if (isBenignTurnInterruption(t, turnId)) {
                    ChatTurnCancellationRegistry.cancel(turnId);
                    releaseActiveTurn.run();
                    return;
                }
                int bufferedLen;
                AgentState currentState;
                synchronized (streamedTextLock) {
                    bufferedLen = streamedContent.length();
                }
                synchronized (turnLock) {
                    currentState = stateMachine.getState();
                }
                AgentRunLogger.error(runLogSnapshot, AgentRunEventType.ERROR,
                        AgentRunLogger.kv(
                                "errorCode", LlmProviderErrorFormatter.resolveErrorCode(t),
                                "errorType", t.getClass().getSimpleName(),
                                "state", currentState == null ? "" : currentState.name(),
                                "elapsedMs", streamStartedAtMs.get() == 0L ? "unknown"
                                        : String.valueOf(System.currentTimeMillis() - streamStartedAtMs.get()),
                                "bufferedLen", bufferedLen),
                        LlmProviderErrorFormatter.formatForLog(t),
                        t);
                logTurnEnd(runLogSnapshot, streamStartedAtMs, turnStepRecorder, AgentState.FAILED);
                terminateTurnWithFailure(
                        chatChatCallback,
                        contextId,
                        turnId,
                        seq,
                        stateMachine,
                        turnLock,
                        terminated,
                        originalCompleteCall,
                        LlmProviderErrorFormatter.resolveErrorCode(t),
                        t,
                        () -> {
                            unbindThinkingOverride.run();
                            persistStreamedAssistant(agentChatMemory, turnConversationId, streamedContent,
                                    streamedReasoning, streamedTextLock, streamedAssistantFlushed, true);
                            StreamedAssistantPersistence.disable(turnConversationId);
                            TurnUsageAccumulator.clear(turnConversationId);
                            TurnRagSourceRegistry.clear(turnConversationId);
                            TurnAskQuestionRegistry.clear(turnConversationId);
                            SubAgentStreamBridge.unbind(turnId);
                            ChatTurnCancellationRegistry.clear(turnId);
                        },
                        flushTurnTrace,
                        releaseActiveTurn);
            };
            Runnable runWebsocketAbort = () -> {
                ChatTurnCancellationRegistry.cancel(turnId);
                boolean claimedTermination = terminated.compareAndSet(false, true);
                try {
                    if (!claimedTermination) {
                        return;
                    }
                    unbindThinkingOverride.run();
                    persistStreamedAssistant(agentChatMemory, turnConversationId, streamedContent,
                            streamedReasoning, streamedTextLock, streamedAssistantFlushed, true);
                    StreamedAssistantPersistence.disable(turnConversationId);
                    TurnUsageAccumulator.clear(turnConversationId);
                    TurnRagSourceRegistry.clear(turnConversationId);
                    TurnAskQuestionRegistry.clear(turnConversationId);
                    SubAgentStreamBridge.unbind(turnId);
                    logTurnEnd(runLogSnapshot, streamStartedAtMs, turnStepRecorder, AgentState.CANCELLED);
                    synchronized (turnLock) {
                        if (stateMachine.getState() != AgentState.COMPLETED
                                && stateMachine.getState() != AgentState.FAILED
                                && stateMachine.getState() != AgentState.CANCELLED) {
                            AgentStateTransition toCancelled = stateMachine.transit(AgentState.CANCELLED, "websocketClosed");
                            recordingResponseCall.accept(AgentEventBuilder.build(
                                    contextId,
                                    turnId,
                                    seq.getAndIncrement(),
                                    stateMachine.getState(),
                                    toCancelled,
                                    AgentEventPhase.COMPLETE,
                                    AgentEventType.SYSTEM,
                                    Map.of("notice", "cancelled")
                            ));
                        }
                    }
                    flushTurnTrace.run();
                    runOriginalCompleteCall(originalCompleteCall);
                } finally {
                    releaseActiveTurn.run();
                }
            };
            chatChatCallback.onWebsocketClose = runWebsocketAbort;
            // 流式接收
            synchronized (turnLock) {
                AgentStateTransition toThinking = stateMachine.transit(AgentState.THINKING, "userMessageAccepted");
                chatChatCallback.responseCall.accept(AgentEventBuilder.build(
                        contextId,
                        turnId,
                        seq.getAndIncrement(),
                        stateMachine.getState(),
                        toThinking,
                        AgentEventPhase.START,
                        AgentEventType.SYSTEM,
                        Map.of("notice", "turn-started")
                ));
                if (!finalAttachments.isEmpty()) {
                    Map<String, Object> attachmentNotice = new HashMap<>();
                    attachmentNotice.put("notice", "user-attachments-ready");
                    attachmentNotice.put("messageIndex", userMessageIndex);
                    attachmentNotice.put("attachments", finalAttachments);
                    chatChatCallback.responseCall.accept(AgentEventBuilder.build(
                            contextId,
                            turnId,
                            seq.getAndIncrement(),
                            stateMachine.getState(),
                            null,
                            AgentEventPhase.START,
                            AgentEventType.NOTICE,
                            attachmentNotice
                    ));
                }
            }
            AtomicInteger retryNo = new AtomicInteger(0);
            Runnable releaseSubAgentBridge = () -> SubAgentStreamBridge.unbind(turnId);
            if (universalAssistant) {
                try {
                    UniversalAssistantOrchestratorService.OrchestrationOutcome outcome =
                            universalAssistantOrchestratorService.orchestrate(
                                    new UniversalAssistantOrchestratorService.OrchestrationRequest(
                                            contextId,
                                            turnId,
                                            userId,
                                            turnConversationId,
                                            toolEventEmitter,
                                            finalAttachments,
                                            limitedUserMessage,
                                            request.getManualOrchestrateAgentId(),
                                            userContext));
                    if (outcome == UniversalAssistantOrchestratorService.OrchestrationOutcome.ORCHESTRATED) {
                        unbindThinkingOverride.run();
                        ChatTurnCancellationRegistry.clearDisposables(turnId);
                        chatChatCallback.completeCall.run();
                        return;
                    }
                } catch (TurnCancelledException ex) {
                    runWebsocketAbort.run();
                    return;
                }
            }
            AgentStreamOptions agentStreamOptions = new AgentStreamOptions(
                    aiAgent,
                    agentRunContext,
                    runLogSnapshot,
                    stateMachine,
                    streamedContent,
                    streamedReasoning,
                    streamedTextLock,
                    turnLock,
                    streamStartedAtMs,
                    retryNo,
                    () -> {
                        unbindThinkingOverride.run();
                        releaseSubAgentBridge.run();
                        ChatTurnCancellationRegistry.clearDisposables(turnId);
                    });
            Disposable disposable = agentStreamSession.stream(agentStreamOptions)
                    .subscribe(parts -> emitAnswerDelta(
                            chatChatCallback,
                            contextId,
                            turnId,
                            seq,
                            stateMachine,
                            turnLock,
                            streamedContent,
                            streamedReasoning,
                            streamedTextLock,
                            index,
                            parts.answerDelta(),
                            parts.reasoningDelta()),
                            chatChatCallback.errorCall,
                            chatChatCallback.completeCall);
            ChatTurnCancellationRegistry.registerDisposable(turnId, disposable);
        } catch (Throwable t) {
            String failedConversationId = conversationIdRef.get();
            AgentRunLogSnapshot failedSnapshot = failedConversationId == null
                    ? null
                    : AgentRunLogContext.lookup(failedConversationId);
            if (failedSnapshot != null) {
                AgentRunLogger.error(failedSnapshot, AgentRunEventType.ERROR,
                        AgentRunLogger.kv(
                                "errorCode", resolveErrorCode(t),
                                "errorType", t.getClass().getSimpleName()),
                        LlmProviderErrorFormatter.formatForLog(t),
                        t);
            }
            if (failedConversationId != null) {
                ThinkingOverrideRegistry.unbind(failedConversationId);
                StreamedAssistantPersistence.disable(failedConversationId);
                TurnUsageAccumulator.clear(failedConversationId);
                TurnRagSourceRegistry.clear(failedConversationId);
                TurnAskQuestionRegistry.clear(failedConversationId);
                SubAgentStreamBridge.unbind(turnId);
                ChatTurnCancellationRegistry.clear(turnId);
                AgentRunLogContext.clear(failedConversationId);
            }
            Runnable releaseActiveTurnOnFailure = () -> {
                String resolvedAgentId = resolvedAgentIdRef.get();
                if (resolvedAgentId != null) {
                    activeChatTurnRegistry.unregister(contextId, resolvedAgentId);
                }
                contextChatCallbackMap.remove(contextId);
            };
            terminateTurnWithFailure(chatChatCallback, contextId, turnId, seq, stateMachine, turnLock,
                    terminated, originalCompleteCall, resolveErrorCode(t), t, null, flushTurnTrace,
                    releaseActiveTurnOnFailure);
        }
    }

    private void emitAnswerDelta(ChatCallback<AgentUiEventEnvelope> chatChatCallback,
                                 String contextId,
                                 String turnId,
                                 AtomicLong seq,
                                 AgentTurnStateMachine stateMachine,
                                 Object turnLock,
                                 StringBuilder streamedContent,
                                 StringBuilder streamedReasoning,
                                 Object streamedTextLock,
                                 int index,
                                 String answerDelta,
                                 String reasoningDelta) {
        if (StringUtils.isNotBlank(answerDelta)
                && StringUtils.isNotBlank(reasoningDelta)
                && answerDelta.equals(reasoningDelta)) {
            reasoningDelta = null;
        }
        if (!StringUtils.isNotBlank(answerDelta) && !StringUtils.isNotBlank(reasoningDelta)) {
            return;
        }
        ChatResponseDto chatResponseDto = buildChatResponseDto(answerDelta, reasoningDelta, index);
        synchronized (streamedTextLock) {
            if (StringUtils.isNotBlank(answerDelta)) {
                streamedContent.append(answerDelta);
            }
            if (StringUtils.isNotBlank(reasoningDelta)) {
                streamedReasoning.append(reasoningDelta);
            }
        }
        synchronized (turnLock) {
            AgentStateTransition transition = null;
            if (StringUtils.isNotBlank(answerDelta)
                    && (stateMachine.getState() == AgentState.THINKING
                    || stateMachine.getState() == AgentState.AGENT_ORCHESTRATING
                    || stateMachine.getState() == AgentState.CALLING_TOOL
                    || stateMachine.getState() == AgentState.LOAD_SKILL)) {
                transition = stateMachine.transit(AgentState.STREAMING_TEXT, "firstAnswerToken");
            }
            chatChatCallback.responseCall.accept(AgentEventBuilder.build(
                    contextId,
                    turnId,
                    seq.getAndIncrement(),
                    stateMachine.getState(),
                    transition,
                    AgentEventPhase.DELTA,
                    AgentEventType.MESSAGE,
                    chatResponseDto
            ));
        }
    }

    /**
     * 下发整轮 FAILED 事件并关闭 WebSocket（幂等，仅首次生效）。
     */
    private void terminateTurnWithFailure(ChatCallback<AgentUiEventEnvelope> callback,
                                          String contextId,
                                          String turnId,
                                          AtomicLong seq,
                                          AgentTurnStateMachine stateMachine,
                                          Object turnLock,
                                          AtomicBoolean terminated,
                                          Runnable originalCompleteCall,
                                          String errorCode,
                                          Throwable cause,
                                          Runnable beforeEmit,
                                          Runnable afterTerminal,
                                          Runnable releaseActiveTurn) {
        if (!terminated.compareAndSet(false, true)) {
            if (releaseActiveTurn != null) {
                releaseActiveTurn.run();
            }
            return;
        }
        try {
            if (beforeEmit != null) {
                beforeEmit.run();
            }
            Consumer<AgentUiEventEnvelope> responseCall = callback.responseCall;
            if (responseCall != null) {
                synchronized (turnLock) {
                    if (stateMachine.getState() != AgentState.FAILED) {
                        responseCall.accept(AgentEventBuilder.buildTurnFailure(
                                contextId, turnId, seq.getAndIncrement(), stateMachine, errorCode, cause));
                    }
                }
            }
            if (afterTerminal != null) {
                afterTerminal.run();
            }
            runOriginalCompleteCall(originalCompleteCall);
        } finally {
            if (releaseActiveTurn != null) {
                releaseActiveTurn.run();
            }
        }
    }

    private static void runOriginalCompleteCall(Runnable originalCompleteCall) {
        if (originalCompleteCall != null) {
            try {
                originalCompleteCall.run();
            } catch (Throwable closeErr) {
                log.warn("WebSocket complete 回调执行异常: {}", closeErr.toString());
            }
        }
    }

    private static boolean isBenignTurnInterruption(Throwable t, String turnId) {
        if (ChatTurnCancellationRegistry.isCancelled(turnId)) {
            return true;
        }
        if (Exceptions.isCancel(t)) {
            return true;
        }
        Throwable cursor = t;
        while (cursor != null) {
            if (cursor instanceof TurnCancelledException) {
                return true;
            }
            if (cursor instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            String name = cursor.getClass().getName();
            if (name.contains("CancelledSubscriber") || name.contains("AbortException")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * 将业务异常映射为前端可识别的 errorCode。
     */
    private static String resolveErrorCode(Throwable t) {
        if (t instanceof IllegalArgumentException) {
            String msg = t.getMessage();
            if (StringUtils.isNotBlank(msg)) {
                if (msg.contains("Unsupported agentId")) {
                    return "unsupportedAgent";
                }
                if (msg.contains("does not own")) {
                    return "contextAccessDenied";
                }
                if (msg.contains("No user message")) {
                    return "noUserMessage";
                }
                if (msg.contains("Knowledge collections are required")) {
                    return "knowledgeCollectionsRequired";
                }
            }
        }
        if (LlmProviderErrorFormatter.isProviderCallFailure(t)) {
            return LlmProviderErrorFormatter.resolveErrorCode(t);
        }
        return "internalError";
    }

    /**
     * 回合终态时将状态轨迹写入记忆（append-only turn_trace 审计行），幂等仅落库一次。
     */
    private void flushTurnTrace(ChatMemory chatMemory,
                                String conversationId,
                                String turnId,
                                TurnStepRecorder turnStepRecorder,
                                AtomicBoolean turnTraceFlushed) {
        if (chatMemory == null || conversationId == null || turnStepRecorder.isEmpty()) {
            return;
        }
        if (!turnTraceFlushed.compareAndSet(false, true)) {
            return;
        }
        try {
            chatMemory.add(conversationId,
                    List.of(chatMemoryMessageCodec.buildTurnTraceAuditMessage(turnId, turnStepRecorder.getSteps())));
        } catch (Exception e) {
            AgentRunLogSnapshot snapshot = AgentRunLogContext.lookup(conversationId);
            if (snapshot != null) {
                AgentRunLogger.warn(snapshot, AgentRunEventType.ERROR,
                        AgentRunLogger.kv("errorCode", "turnTracePersistFailed",
                                "errorType", e.getClass().getSimpleName()),
                        e.toString());
            }
        }
    }

    private static void logTurnEnd(AgentRunLogSnapshot snapshot,
                                   AtomicLong streamStartedAtMs,
                                   TurnStepRecorder turnStepRecorder,
                                   AgentState status) {
        if (snapshot == null) {
            return;
        }
        long elapsedMs = streamStartedAtMs.get() == 0L
                ? 0L
                : System.currentTimeMillis() - streamStartedAtMs.get();
        AgentRunLogger.info(snapshot, AgentRunEventType.TURN_END,
                AgentRunLogger.kv(
                        "status", status == null ? "" : status.name(),
                        "elapsedMs", elapsedMs,
                        "stepCount", turnStepRecorder == null ? 0 : turnStepRecorder.getSteps().size()),
                "turn finished");
    }

    private static String resolveLogUserId(String userId) {
        return StringUtils.isNotBlank(userId) ? userId.trim() : ANONYMOUS_USER;
    }

    /**
     * 将本轮流式累加的 {@code streamedContent}/{@code streamedReasoning} 写入记忆（正常结束或中断补偿，幂等一次）。
     */
    void persistStreamedAssistant(ChatMemory chatMemory, String conversationId,
                                  StringBuilder streamedContent,
                                  StringBuilder streamedReasoning,
                                  Object streamedTextLock,
                                  AtomicBoolean once,
                                  boolean addEllipsis) {
        if (!once.compareAndSet(false, true)) {
            return;
        }
        String content;
        String reasoning;
        synchronized (streamedTextLock) {
            content = streamedContent.toString();
            reasoning = streamedReasoning.toString();
        }
        String ragInfosJson = TurnRagSourceRegistry.drainRagInfosJson(conversationId);
        String pendingQuestionJson = TurnAskQuestionRegistry.drainQuestionJson(conversationId);
        if (content.isEmpty() && reasoning.isEmpty()
                && StringUtils.isBlank(ragInfosJson)
                && StringUtils.isBlank(pendingQuestionJson)) {
            return;
        }
        chatMemory.add(conversationId,
                List.of(buildStreamedAssistantMessage(content, reasoning, addEllipsis,
                        ragInfosJson, pendingQuestionJson)));
    }

    /**
     * 构造流式落库用的 assistant 消息，与 {@link ChatMemoryMessageCodec} 编码路径对齐。
     */
    static AssistantMessage buildStreamedAssistantMessage(String content, String reasoning, boolean addEllipsis,
                                                          String ragInfosJson) {
        return buildStreamedAssistantMessage(content, reasoning, addEllipsis, ragInfosJson, null);
    }

    /**
     * 构造流式落库用的 assistant 消息，可附带 pendingQuestion 元数据。
     */
    static AssistantMessage buildStreamedAssistantMessage(String content, String reasoning, boolean addEllipsis,
                                                          String ragInfosJson, String pendingQuestionJson) {
        String finalContent = content != null ? content : "";
        if (addEllipsis && StringUtils.isNotBlank(finalContent)) {
            finalContent = finalContent + "...";
        }
        String finalReasoning = reasoning != null ? reasoning : "";
        if (addEllipsis && StringUtils.isNotBlank(finalReasoning)) {
            finalReasoning = finalReasoning + "...";
        }
        AssistantMessage.Builder builder = AssistantMessage.builder().content(finalContent);
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        if (StringUtils.isNotBlank(finalReasoning)) {
            properties.put(SpringAiReasoningMetadataAdapter.UNIFIED_REASONING_KEY, finalReasoning);
        }
        if (StringUtils.isNotBlank(ragInfosJson)) {
            properties.put(ChatMemoryMessageCodec.META_RAG_INFOS, ragInfosJson);
        }
        if (StringUtils.isNotBlank(pendingQuestionJson)) {
            properties.put(ChatMemoryMessageCodec.META_PENDING_QUESTION, pendingQuestionJson);
        }
        if (!properties.isEmpty()) {
            builder.properties(properties);
        }
        return builder.build();
    }

    /**
     * 构造 Spring AI 记忆使用的会话键，格式为 userId:contextId:agentId。
     */
    private String buildConversationId(String userId, String contextId, String resolvedAgentId) {
        return ConversationIdCodec.format(userId == null ? ANONYMOUS_USER : userId, contextId, resolvedAgentId);
    }

    /**
     * 构造前端沿用的 ChatResponseDto 增量消息。
     */
    private ChatResponseDto buildChatResponseDto(String answerDelta, String reasoningDelta, int index) {
        ChatResponseDto chatResponseDto = new ChatResponseDto();
        MessageDto messageDto = new MessageDto();
        messageDto.setRole(MessageDto.RoleEnum.ASSISTANT);
        messageDto.setIndex(index);
        if (StringUtils.isNotBlank(answerDelta)) {
            messageDto.setContent(answerDelta);
        }
        if (StringUtils.isNotBlank(reasoningDelta)) {
            messageDto.setReasoningContent(reasoningDelta);
        }
        chatResponseDto.setMessage(messageDto);
        return chatResponseDto;
    }

    /**
     * 从请求消息列表中逆序提取最新一条用户消息，作为本轮输入。
     */
    private String extractLatestUserMessage(List<MessageDto> messages) {
        return extractLatestUser(messages).getContent();
    }

    private MessageDto extractLatestUser(List<MessageDto> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageDto message = messages.get(i);
            if (message.getRole() == MessageDto.RoleEnum.USER) {
                return message;
            }
        }
        throw new IllegalArgumentException("No user message found in request.");
    }

    private static List<String> normalizeKnowledgeCollections(List<String> rawCollections) {
        if (CollectionUtils.isEmpty(rawCollections)) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : rawCollections) {
            String collection = StringUtils.trimToNull(raw);
            if (collection != null) {
                normalized.add(collection);
            }
        }
        return new ArrayList<>(normalized);
    }

    /**
     * 控制单条用户输入长度，避免超长文本导致 token 膨胀。
     */
    private String limitMessageLength(String message) {
        if (message == null) {
            return "";
        }
        int maxLen = chatInputProperties.getMaxUserMessageLength();
        if (maxLen > 0 && message.length() > maxLen) {
            log.warn("User message truncated from {} to {}", message.length(), maxLen);
            return message.substring(0, maxLen);
        }
        return message;
    }
}
