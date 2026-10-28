package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatCallback;
import io.github.jerryt92.j2agent.service.llm.ActiveChatTurnRegistry;
import io.github.jerryt92.j2agent.service.llm.ChatContextService;
import io.github.jerryt92.j2agent.service.llm.ChatService;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnControlService;
import io.github.jerryt92.j2agent.service.llm.queue.*;
import io.github.jerryt92.j2agent.service.security.LoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import java.util.HashMap;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatControllerCloseTest {
    private ChatController controller;
    private ChatCallbackRegistry callbacks;
    private ChatTurnControlService control;
    private ChatInputQueueManager queue;
    private WebSocketSession session;
    private Map<String, Object> attributes;
    private ChatCallback<AgentUiEventEnvelope> callback;

    @BeforeEach
    void setup() {
        callbacks = mock(ChatCallbackRegistry.class);
        control = mock(ChatTurnControlService.class);
        queue = mock(ChatInputQueueManager.class);
        controller = new ChatController(mock(ChatContextService.class), mock(ChatService.class),
                mock(LoginService.class), mock(AgentRouter.class), callbacks, queue,
                mock(ChatOutputDispatcher.class), mock(ChatOutputEventCache.class),
                mock(ActiveChatTurnRegistry.class), control);
        session = mock(WebSocketSession.class);
        attributes = new HashMap<>();
        callback = new ChatCallback<>("sender");
        attributes.put("contextId", "ctx");
        attributes.put("agentId", "agent");
        attributes.put("callback", callback);
        when(session.getAttributes()).thenReturn(attributes);
    }

    private void verifyStopped() {
        verify(callbacks).markCancelled("ctx", "agent", "sender");
        verify(callbacks).markSessionCancelled("ctx", "agent");
        verify(control).cancelSession("ctx", "agent", "user interrupt");
    }

    @Test
    void ordinaryCloseStopsSessionByDefault() throws Exception {
        controller.afterConnectionClosed(session, CloseStatus.NORMAL);
        verifyStopped();
        verify(callbacks).unregister("ctx", "agent", "sender");
    }

    @Test
    void backgroundCloseDoesNotStopSession() throws Exception {
        attributes.put("chatBackground", true);
        controller.afterConnectionClosed(session, CloseStatus.NORMAL);
        verifyNoInteractions(control);
        verify(callbacks, never()).markSessionCancelled(anyString(), anyString());
    }

    @Test
    void explicitInterruptStopsEvenInBackground() throws Exception {
        attributes.put("chatBackground", true);
        controller.afterConnectionClosed(session, new CloseStatus(1000, "user interrupt"));
        verifyStopped();
    }

    @Test
    void observerCloseDoesNotStopSession() throws Exception {
        attributes.put("chatObserver", true);
        controller.afterConnectionClosed(session, CloseStatus.NORMAL);
        verifyNoInteractions(control);
    }

    @Test
    void serverCompletionDoesNotStopSession() throws Exception {
        when(queue.enqueue(any())).thenReturn(ChatEnqueueResult.enqueued());
        controller.handleTextMessage(session, new TextMessage("{}"));
        callback.completeCall.run();
        controller.afterConnectionClosed(session, CloseStatus.NORMAL);
        verify(session).close();
        verifyNoInteractions(control);
    }

    @Test
    void latestMessageDeterminesModeAndSubmittingLeavesObserverMode() throws Exception {
        attributes.put("chatObserver", true);
        when(queue.enqueue(any())).thenReturn(ChatEnqueueResult.enqueued());
        controller.handleTextMessage(session, new TextMessage("{\"background\":true}"));
        controller.handleTextMessage(session, new TextMessage("{}"));
        controller.afterConnectionClosed(session, CloseStatus.NORMAL);
        verifyStopped();
    }

    @Test
    void latestTrueEnablesBackgroundAfterForegroundMessage() throws Exception {
        when(queue.enqueue(any())).thenReturn(ChatEnqueueResult.enqueued());
        controller.handleTextMessage(session, new TextMessage("{}"));
        controller.handleTextMessage(session, new TextMessage("{\"background\":true}"));
        controller.afterConnectionClosed(session, CloseStatus.NORMAL);
        verifyNoInteractions(control);
    }
}
