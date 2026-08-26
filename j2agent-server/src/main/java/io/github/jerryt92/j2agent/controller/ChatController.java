package io.github.jerryt92.j2agent.controller;

import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.config.web.AutoRegisterWebSocketHandler;
import io.github.jerryt92.j2agent.config.web.TraceIdContext;
import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentInfoList;
import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.model.AgentStateTransition;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatCallback;
import io.github.jerryt92.j2agent.model.ChatContextDto;
import io.github.jerryt92.j2agent.model.ChatRequestDto;
import io.github.jerryt92.j2agent.model.CheckApiResponse;
import io.github.jerryt92.j2agent.model.ContextIdDto;
import io.github.jerryt92.j2agent.model.HistoryContextList;
import io.github.jerryt92.j2agent.model.MessageFeedbackRequest;
import io.github.jerryt92.j2agent.model.Translator;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.server.api.ChatApi;
import io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentUrlResolver;
import io.github.jerryt92.j2agent.service.llm.ActiveChatTurnRegistry;
import io.github.jerryt92.j2agent.service.llm.AgentEventBuilder;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.ChatContextBo;
import io.github.jerryt92.j2agent.service.llm.ChatContextService;
import io.github.jerryt92.j2agent.service.llm.ChatService;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnControlService;
import io.github.jerryt92.j2agent.service.llm.queue.ChatCallbackRegistry;
import io.github.jerryt92.j2agent.service.llm.queue.ChatEnqueueResult;
import io.github.jerryt92.j2agent.service.llm.queue.ChatInputQueueManager;
import io.github.jerryt92.j2agent.service.llm.queue.ChatOutputDispatcher;
import io.github.jerryt92.j2agent.service.llm.queue.ChatOutputEventCache;
import io.github.jerryt92.j2agent.service.llm.queue.ChatOutputSnapshot;
import io.github.jerryt92.j2agent.service.llm.queue.ChatTurnInputTask;
import io.github.jerryt92.j2agent.service.llm.universal.UniversalAssistantConstants;
import io.github.jerryt92.j2agent.service.security.LoginService;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Log4j2
@RestController
@Qualifier("j2agent.alive.checker")
@AutoRegisterWebSocketHandler(path = "/ws/rest/j2agent/chat", allowedOrigin = "*", interceptorsClassNames = {"io.github.jerryt92.j2agent.interceptor.WebsocketLoginInterceptor"})
public class ChatController extends AbstractWebSocketHandler implements ChatApi {
    private static final String AGENT_ID_PARAM = "agent-id";
    private static final String AGENT_ID_ATTRIBUTE = "agentId";

    private final ChatContextService chatContextService;
    private final ChatService chatService;
    private final LoginService loginService;
    private final AgentRouter agentRouter;
    private final ChatCallbackRegistry chatCallbackRegistry;
    private final ChatInputQueueManager chatInputQueueManager;
    private final ChatOutputDispatcher chatOutputDispatcher;
    private final ChatOutputEventCache chatOutputEventCache;
    private final ActiveChatTurnRegistry activeChatTurnRegistry;
    private final ChatTurnControlService chatTurnControlService;
    @Autowired(required = false)
    private ChatAttachmentUrlResolver chatAttachmentUrlResolver;

    public ChatController(ChatContextService chatContextService, ChatService chatService, LoginService loginService,
                          AgentRouter agentRouter,
                          ChatCallbackRegistry chatCallbackRegistry,
                          ChatInputQueueManager chatInputQueueManager,
                          ChatOutputDispatcher chatOutputDispatcher,
                          ChatOutputEventCache chatOutputEventCache,
                          ActiveChatTurnRegistry activeChatTurnRegistry,
                          ChatTurnControlService chatTurnControlService) {
        this.chatContextService = chatContextService;
        this.chatService = chatService;
        this.loginService = loginService;
        this.agentRouter = agentRouter;
        this.chatCallbackRegistry = chatCallbackRegistry;
        this.chatInputQueueManager = chatInputQueueManager;
        this.chatOutputDispatcher = chatOutputDispatcher;
        this.chatOutputEventCache = chatOutputEventCache;
        this.activeChatTurnRegistry = activeChatTurnRegistry;
        this.chatTurnControlService = chatTurnControlService;
    }

    @Override
    public ResponseEntity<CheckApiResponse> checkApi() {
        CheckApiResponse response = new CheckApiResponse();
        response.setStatus(CheckApiResponse.StatusEnum.NORMAL);
        response.setDescription("AI center api is normal.");
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<ChatContextDto> getHistoryContext(String contextId, String agentId) {
        UserContextBo session = loginService.getSession();
        if (session == null || !StringUtils.isNotBlank(session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login is required");
        }
        ChatContextBo chatContextBo = chatContextService.getChatContext(contextId, session.getUserId(), agentId);
        boolean ragSourceDisplayEnabled = UniversalAssistantConstants.isUniversalAssistant(agentId)
                || UniversalAssistantConstants.isKnowledgeQaAssistant(agentId) || agentRouter.route(agentId).isRagSourceDisplayEnabled();
        ChatContextDto dto = chatContextBo == null
                ? new ChatContextDto().messages(List.of())
                : Translator.translateToChatContextDto(chatContextBo, ragSourceDisplayEnabled);
        if (dto != null && chatAttachmentUrlResolver != null) {
            chatAttachmentUrlResolver.applyToChatContext(dto);
        }
        return ResponseEntity.ok(dto);
    }

    @Override
    public ResponseEntity<HistoryContextList> getHistoryContextList(String agentId, Integer offset, Integer limit) {
        UserContextBo session = loginService.getSession();
        if (session == null || !StringUtils.isNotBlank(session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login is required");
        }
        return ResponseEntity.ok(chatContextService.getHistoryContextList(offset, limit, agentId));
    }

    @Override
    public ResponseEntity<Void> deleteHistoryContext(List<String> contextId, Boolean clearAll, String agentId) {
        if (Boolean.TRUE.equals(clearAll)) {
            chatContextService.clearAllHistoryContext(agentId);
        } else if (contextId == null || contextId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "context-id is required unless clear-all=true");
        } else {
            chatContextService.deleteHistoryContext(contextId, agentId);
        }
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<ContextIdDto> getNewContextId() {
        return ResponseEntity.ok(
                new ContextIdDto().contextId(UUIDv7Utils.randomUUIDv7())
        );
    }

    /**
     * 列出当前进程内已注册的全部智能体。
     */
    @Override
    public ResponseEntity<AgentInfoList> listAgents() {
        UserContextBo session = loginService.getSession();
        return ResponseEntity.ok(agentRouter.listRegisteredAgents(session == null ? null : session.getLanguage()));
    }

    @Override
    public ResponseEntity<Void> addMessageFeedback(MessageFeedbackRequest messageFeedbackRequest) {
        chatContextService.addMessageFeedback(messageFeedbackRequest);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/v1/rest/j2agent/chat/stop")
    public ResponseEntity<Void> stopChatTurn(@RequestParam("context-id") String contextId,
                                             @RequestParam("agent-id") String agentId) {
        UserContextBo session = loginService.getSession();
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login is required");
        }
        if (StringUtils.isBlank(contextId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "context-id is required");
        }
        if (StringUtils.isBlank(agentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent-id is required");
        }
        stopSession(contextId, agentId);
        return ResponseEntity.ok().build();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String contextId = getParam("context-id", Objects.requireNonNull(session.getUri()).toString());
        String agentId = getParam(AGENT_ID_PARAM, Objects.requireNonNull(session.getUri()).toString());
        if (StringUtils.isBlank(contextId)) {
            sendHandshakeFailure(session, null, "context_id_not_found", "context-id is required");
            closeSession(session);
            return;
        }
        if (StringUtils.isBlank(agentId)) {
            sendHandshakeFailure(session, contextId, "agent_id_not_found", "agent-id is required");
            closeSession(session);
            return;
        }
        session.getAttributes().put("contextId", contextId);
        session.getAttributes().put(AGENT_ID_ATTRIBUTE, agentId);
        ChatCallback<AgentUiEventEnvelope> callback = new ChatCallback<>(UUIDv7Utils.randomUUIDv7());
        session.getAttributes().put("callback", callback);
        UserContextBo userContextBo = (UserContextBo) session.getAttributes().get(LoginService.LOGIN_ATTRIBUTE);
        if (userContextBo == null) {
            sendHandshakeFailure(session, contextId, "loginMissing", "login is required");
            closeSession(session);
            return;
        }
        bindWebSocketCallback(session, callback);
        if (isTrue(getParam("resume", Objects.requireNonNull(session.getUri()).toString()))) {
            chatCallbackRegistry.register(contextId, agentId, callback.subscriptionId, callback);
            sendConnectedNotice(session, contextId);
            handleResumeConnection(contextId, agentId, callback.subscriptionId);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) {
        ChatRequestDto chatRequestDto = JSONObject.parseObject(message.getPayload(), ChatRequestDto.class);
        String contextId = (String) wsSession.getAttributes().get("contextId");
        try {
            AgentUiEventEnvelope agentUiEventEnvelope = new AgentUiEventEnvelope()
                    .setContextId(contextId)
                    .setTurnId(UUIDv7Utils.randomUUIDv7())
                    .setSeq(0L)
                    .setState(AgentState.IDLE)
                    .setPhase(AgentEventPhase.START)
                    .setEventType(AgentEventType.SYSTEM)
                    .setTransition(new AgentStateTransition().setFrom(AgentState.IDLE).setTo(AgentState.IDLE).setReason("wsConnected"))
                    .setPayload(new HashMap<>(java.util.Map.of("notice", "connected")))
                    .setTs(System.currentTimeMillis())
                    .setEventId(UUIDv7Utils.randomUUIDv7());
            wsSession.sendMessage(new TextMessage(JSONObject.toJSONString(agentUiEventEnvelope)));
        } catch (IOException e) {
            closeSession(wsSession);
        }
        UserContextBo userContextBo = (UserContextBo) wsSession.getAttributes().get(LoginService.LOGIN_ATTRIBUTE);
        ChatCallback<AgentUiEventEnvelope> chatChatCallback = getChatCallback(wsSession);
        String agentId = (String) wsSession.getAttributes().get(AGENT_ID_ATTRIBUTE);
        if (StringUtils.isBlank(chatRequestDto.getContextId())) {
            chatRequestDto.setContextId(contextId);
        }
        bindWebSocketCallback(wsSession, chatChatCallback);
        chatCallbackRegistry.register(contextId, agentId, chatChatCallback.subscriptionId, chatChatCallback);
        chatCallbackRegistry.clearSessionCancelled(contextId, agentId);
        // 每轮对话生成独立 traceId；入队后由 worker 恢复到 MDC
        TraceIdContext.clear();
        String traceId = TraceIdContext.currentOrNew();
        ChatTurnInputTask task = new ChatTurnInputTask(
                contextId,
                agentId,
                chatChatCallback.subscriptionId,
                UUIDv7Utils.randomUUIDv7(),
                chatRequestDto,
                userContextBo,
                System.currentTimeMillis(),
                traceId);
        ChatEnqueueResult enqueueResult = chatInputQueueManager.enqueue(task);
        if (enqueueResult.status() == ChatEnqueueResult.Status.ENQUEUED) {
            TraceIdContext.clear();
            return;
        }
        if (enqueueResult.status() == ChatEnqueueResult.Status.DISABLED) {
            try {
                chatService.handleChat(chatChatCallback, chatRequestDto, userContextBo, agentId);
            } finally {
                TraceIdContext.clear();
            }
            return;
        }
        TraceIdContext.clear();
        chatOutputDispatcher.fail(contextId, agentId, chatChatCallback.subscriptionId,
                enqueueResult.errorCode(), new IllegalStateException(enqueueResult.errorMessage()));
    }

    private void bindWebSocketCallback(WebSocketSession wsSession,
                                       ChatCallback<AgentUiEventEnvelope> chatChatCallback) {
        chatChatCallback.responseCall = chatResponse -> {
            if (!wsSession.isOpen()) {
                return;
            }
            try {
                wsSession.sendMessage(new TextMessage(JSONObject.toJSONString(chatResponse)));
            } catch (IllegalStateException ex) {
                if (log.isDebugEnabled()) {
                    log.debug("WebSocket 已关闭，跳过事件下发: {}", ex.getMessage());
                }
            } catch (IOException ex) {
                log.warn("WebSocket 写入失败: {}", ex.getMessage());
                closeSession(wsSession);
            }
        };
        chatChatCallback.completeCall = () -> closeSession(wsSession);
    }

    private void sendConnectedNotice(WebSocketSession session, String contextId) {
        try {
            AgentUiEventEnvelope agentUiEventEnvelope = new AgentUiEventEnvelope()
                    .setContextId(contextId)
                    .setTurnId(UUIDv7Utils.randomUUIDv7())
                    .setSeq(0L)
                    .setState(AgentState.IDLE)
                    .setPhase(AgentEventPhase.START)
                    .setEventType(AgentEventType.SYSTEM)
                    .setTransition(new AgentStateTransition().setFrom(AgentState.IDLE).setTo(AgentState.IDLE).setReason("wsConnected"))
                    .setPayload(new HashMap<>(java.util.Map.of("notice", "connected")))
                    .setTs(System.currentTimeMillis())
                    .setEventId(UUIDv7Utils.randomUUIDv7());
            session.sendMessage(new TextMessage(JSONObject.toJSONString(agentUiEventEnvelope)));
        } catch (IOException e) {
            closeSession(session);
        }
    }

    private void handleResumeConnection(String contextId, String agentId, String subscriptionId) {
        if (sendSnapshotIfPresent(contextId, agentId, subscriptionId)) {
            return;
        }
        if (activeChatTurnRegistry.isActive(contextId, agentId) || chatInputQueueManager.size(contextId, agentId) > 0) {
            return;
        }
        AgentUiEventEnvelope envelope = AgentEventBuilder.build(
                contextId,
                UUIDv7Utils.randomUUIDv7(),
                0L,
                AgentState.COMPLETED,
                null,
                AgentEventPhase.COMPLETE,
                AgentEventType.SYSTEM,
                new HashMap<>(Map.of("notice", "resume-empty")));
        chatOutputDispatcher.dispatch(contextId, agentId, subscriptionId, envelope);
        chatOutputDispatcher.complete(contextId, agentId, subscriptionId);
    }

    private boolean sendSnapshotIfPresent(String contextId, String agentId, String subscriptionId) {
        ChatOutputSnapshot snapshot = chatOutputEventCache.getSnapshot(contextId, agentId);
        if (snapshot == null || StringUtils.isBlank(snapshot.getTurnId())) {
            return false;
        }
        long seq = 0L;
        if (snapshot.getStateTrail() != null) {
            for (ChatOutputSnapshot.StateTrailItem item : snapshot.getStateTrail()) {
                if (item == null || item.getState() == null) {
                    continue;
                }
                AgentUiEventEnvelope stateEnvelope = AgentEventBuilder.build(
                        contextId,
                        snapshot.getTurnId(),
                        seq++,
                        item.getState(),
                        item.getTransition(),
                        item.getPhase(),
                        item.getEventType(),
                        item.getPayload());
                if (item.getTs() > 0) {
                    stateEnvelope.setTs(item.getTs());
                }
                chatOutputDispatcher.dispatch(contextId, agentId, subscriptionId, stateEnvelope);
            }
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("snapshot", true);
        payload.put("answerContent", StringUtils.defaultString(snapshot.getAnswerContent()));
        payload.put("reasoningContent", StringUtils.defaultString(snapshot.getReasoningContent()));
        payload.put("updatedAt", snapshot.getUpdatedAt());
        AgentUiEventEnvelope envelope = AgentEventBuilder.build(
                contextId,
                snapshot.getTurnId(),
                seq,
                snapshot.getState() == null ? AgentState.STREAMING_TEXT : snapshot.getState(),
                null,
                AgentEventPhase.DELTA,
                AgentEventType.MESSAGE,
                payload);
        chatOutputDispatcher.dispatch(contextId, agentId, subscriptionId, envelope);
        return true;
    }

    /**
     * 握手阶段校验失败时，向前端下发整轮 FAILED 事件后由调用方关闭连接。
     */
    private void sendHandshakeFailure(WebSocketSession session,
                                      String contextId,
                                      String errorCode,
                                      String errorMessage) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            AgentTurnStateMachine stateMachine = new AgentTurnStateMachine();
            AgentUiEventEnvelope envelope = AgentEventBuilder.buildTurnFailure(
                    contextId,
                    UUIDv7Utils.randomUUIDv7(),
                    0L,
                    stateMachine,
                    errorCode,
                    null);
            envelope.setPayload(AgentEventBuilder.buildErrorPayload(errorCode, errorMessage));
            session.sendMessage(new TextMessage(JSONObject.toJSONString(envelope)));
        } catch (IOException ex) {
            log.warn("下发握手失败事件失败: {}", ex.getMessage());
        }
    }

    private void closeSession(WebSocketSession session) {
        try {
            session.close();
        } catch (Throwable t) {
            log.error("", t);
        }
    }

    @SuppressWarnings("unchecked")
    private ChatCallback<AgentUiEventEnvelope> getChatCallback(WebSocketSession session) {
        Object callbackObj = session.getAttributes().get("callback");
        if (callbackObj instanceof ChatCallback) {
            return (ChatCallback<AgentUiEventEnvelope>) callbackObj;
        }
        throw new IllegalStateException("Callback attribute is missing or invalid");
    }


    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String contextId = (String) session.getAttributes().get("contextId");
        String agentId = (String) session.getAttributes().get(AGENT_ID_ATTRIBUTE);
        ChatCallback<AgentUiEventEnvelope> chatCallback = getNullableChatCallback(session);
        if (chatCallback == null) {
            super.afterConnectionClosed(session, status);
            return;
        }
        if (StringUtils.isNotBlank(contextId) && StringUtils.isNotBlank(agentId)) {
            if ("user interrupt".equals(status.getReason())) {
                chatCallbackRegistry.markCancelled(contextId, agentId, chatCallback.subscriptionId);
                stopSession(contextId, agentId);
            }
            chatCallbackRegistry.unregister(contextId, agentId, chatCallback.subscriptionId);
        } else if (chatCallback.onWebsocketClose != null) {
            chatCallback.onWebsocketClose.run();
        }
        super.afterConnectionClosed(session, status);
    }

    private void stopSession(String contextId, String agentId) {
        boolean firstSessionCancel = chatCallbackRegistry.markSessionCancelled(contextId, agentId);
        String cancelledTurnId = chatTurnControlService.cancelSession(contextId, agentId, "user interrupt");
        if (firstSessionCancel || StringUtils.isNotBlank(cancelledTurnId)) {
            chatOutputDispatcher.cancelSession(contextId, agentId, cancelledTurnId);
        }
    }

    @SuppressWarnings("unchecked")
    private ChatCallback<AgentUiEventEnvelope> getNullableChatCallback(WebSocketSession session) {
        Object callbackObj = session.getAttributes().get("callback");
        if (callbackObj instanceof ChatCallback) {
            return (ChatCallback<AgentUiEventEnvelope>) callbackObj;
        }
        return null;
    }

    private static String getParam(String param, String url) {
        if (url != null) {
            // 找到查询参数部分（?后面的部分）
            int queryStart = url.indexOf('?');
            if (queryStart != -1 && queryStart < url.length() - 1) {
                String queryString = url.substring(queryStart + 1);
                String[] params = queryString.split("&");
                for (String p : params) {
                    String[] keyValue = p.split("=", 2); // 限制分割成2部分
                    if (keyValue.length >= 1 && keyValue[0].equals(param)) {
                        return keyValue.length >= 2 ? keyValue[1] : null;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isTrue(String value) {
        return "true".equalsIgnoreCase(StringUtils.trimToEmpty(value));
    }
}
