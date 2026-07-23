package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatCallback;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.ThinkingOverrideRegistry;
import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamOptions;
import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamSession;
import io.github.jerryt92.j2agent.service.llm.agent.StreamingTextParts;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunContext;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import io.github.jerryt92.j2agent.service.llm.chat.TurnCancelledException;
import io.github.jerryt92.j2agent.service.llm.tool.AgentUiToolEventInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class UniversalSubAgentCallServiceTest {

    private AgentRouter agentRouter;
    private AgentStreamSession agentStreamSession;
    private UniversalSubAgentCallService subAgentCallService;

    @BeforeEach
    void setUp() {
        agentRouter = Mockito.mock(AgentRouter.class);
        agentStreamSession = Mockito.mock(AgentStreamSession.class);
        subAgentCallService = new UniversalSubAgentCallService(agentRouter, agentStreamSession);
    }

    @AfterEach
    void tearDown() {
        SubAgentStreamBridge.unbind("turn-1");
        ChatTurnCancellationRegistry.clear("turn-1");
        ThinkingOverrideRegistry.unbind("user-1:ctx-1:universal_assistant");
        ThinkingOverrideRegistry.unbind("user-1:ctx-1:j2agent-qa-assistant");
    }

    @Test
    void callUsesSpecialistConversationIdAndFullMemory() {
        AiAgent wikiAgent = Mockito.mock(AiAgent.class);
        when(wikiAgent.getAgentId()).thenReturn("j2agent-qa-assistant");
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(wikiAgent));
        when(agentRouter.route("j2agent-qa-assistant")).thenReturn(wikiAgent);

        ArgumentCaptor<AgentStreamOptions> optionsCaptor = ArgumentCaptor.forClass(AgentStreamOptions.class);
        when(agentStreamSession.stream(optionsCaptor.capture()))
                .thenReturn(Flux.just(new StreamingTextParts("wiki answer", null)));

        StringBuilder streamedContent = new StringBuilder();
        AgentTurnStateMachine stateMachine = new AgentTurnStateMachine();
        Object turnLock = new Object();
        SubAgentStreamBridge.bind("turn-1", new SubAgentStreamBridge.Target(
                null,
                "ctx-1",
                "turn-1",
                "user-1",
                "user-1:ctx-1:universal_assistant",
                null,
                new AtomicLong(0),
                stateMachine, turnLock, streamedContent, new StringBuilder(), new Object(), 0));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put(AgentRunnableContextKeys.CONTEXT_KEY_CONTEXT_ID, "ctx-1");
        ctx.put(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1");
        ctx.put(AgentRunnableContextKeys.CONTEXT_KEY_USER_ID, "user-1");
        ctx.put(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, "user-1:ctx-1:universal_assistant");
        ctx.put(AgentUiToolEventInterceptor.CONTEXT_KEY_TOOL_EVENT_EMITTER, null);

        String result = subAgentCallService.call(
                "j2agent-qa-assistant",
                "查文档",
                new UniversalSubAgentCallService.SubAgentCallRequest(
                        "ctx-1", "turn-1", "user-1", "user-1:ctx-1:universal_assistant", null, List.of(),
                        userContext("zh_CN")));

        assertEquals("wiki answer", result);
        AgentRunContext runContext = optionsCaptor.getValue().agentRunContext();
        assertEquals("user-1:ctx-1:j2agent-qa-assistant", runContext.conversationId());
        assertTrue(runContext.subAgentCallRun());
    }

    @Test
    void callForwardsAttachmentsToAgentRunContext() {
        AiAgent wikiAgent = Mockito.mock(AiAgent.class);
        when(wikiAgent.getAgentId()).thenReturn("j2agent-qa-assistant");
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(wikiAgent));
        when(agentRouter.route("j2agent-qa-assistant")).thenReturn(wikiAgent);

        ArgumentCaptor<AgentStreamOptions> optionsCaptor = ArgumentCaptor.forClass(AgentStreamOptions.class);
        when(agentStreamSession.stream(optionsCaptor.capture()))
                .thenReturn(Flux.just(new StreamingTextParts("wiki answer", null)));

        ChatAttachmentDto attachment = new ChatAttachmentDto().objectKey("chat/u/c/a.png").name("a.png");
        String result = subAgentCallService.call(
                "j2agent-qa-assistant",
                "routing",
                new UniversalSubAgentCallService.SubAgentCallRequest(
                        "ctx-1", "turn-1", "user-1", "user-1:ctx-1:universal_assistant", null, List.of(attachment),
                        userContext("zh_CN")));

        assertEquals("wiki answer", result);
        assertEquals(1, optionsCaptor.getValue().agentRunContext().attachments().size());
        assertEquals("chat/u/c/a.png", optionsCaptor.getValue().agentRunContext().attachments().get(0).getObjectKey());
    }

    @Test
    void callUsesTargetAgentThinkingOverrideInsteadOfParentOverride() {
        AiAgent qaAgent = Mockito.mock(AiAgent.class);
        when(qaAgent.getAgentId()).thenReturn("j2agent-qa-assistant");
        when(qaAgent.getThinkingOverride()).thenReturn(AgentThinkingOverride.ON);
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(qaAgent));
        when(agentRouter.route("j2agent-qa-assistant")).thenReturn(qaAgent);

        ThinkingOverrideRegistry.bind("user-1:ctx-1:universal_assistant", AgentThinkingOverride.OFF);

        when(agentStreamSession.stream(Mockito.any()))
                .thenAnswer(invocation -> {
                    AgentStreamOptions options = invocation.getArgument(0);
                    AgentThinkingOverride bound = ThinkingOverrideRegistry.get(
                            options.agentRunContext().conversationId());
                    assertEquals(AgentThinkingOverride.ON, bound);
                    return Flux.just(new StreamingTextParts("qa answer", null));
                });

        String result = subAgentCallService.call(
                "j2agent-qa-assistant",
                "routing",
                new UniversalSubAgentCallService.SubAgentCallRequest(
                        "ctx-1", "turn-1", "user-1", "user-1:ctx-1:universal_assistant", null, List.of(),
                        userContext("zh_CN")));

        assertEquals("qa answer", result);
    }

    @Test
    void callRejectsUnknownAgent() {
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of());
        String result = subAgentCallService.call(
                "missing",
                "q",
                new UniversalSubAgentCallService.SubAgentCallRequest(
                        "ctx-1", "turn-1", "user-1", "user-1:ctx-1:universal_assistant", null, List.of(),
                        userContext("zh_CN")));
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void callThrowsWhenTurnAlreadyCancelled() {
        ChatTurnCancellationRegistry.cancel("turn-1");
        assertThrows(TurnCancelledException.class, () -> subAgentCallService.call(
                "j2agent-qa-assistant",
                "q",
                new UniversalSubAgentCallService.SubAgentCallRequest(
                        "ctx-1", "turn-1", "user-1", "user-1:ctx-1:universal_assistant", null, List.of(),
                        userContext("zh_CN"))));
    }

    @Test
    void callStopsStreamingWhenTurnCancelledMidFlight() {
        AiAgent wikiAgent = Mockito.mock(AiAgent.class);
        when(wikiAgent.getAgentId()).thenReturn("j2agent-qa-assistant");
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(wikiAgent));
        when(agentRouter.route("j2agent-qa-assistant")).thenReturn(wikiAgent);

        when(agentStreamSession.stream(Mockito.any()))
                .thenReturn(Flux.concat(
                        Mono.just(new StreamingTextParts("part-", null)),
                        Mono.delay(Duration.ofSeconds(30)).then(Mono.just(new StreamingTextParts("tail", null)))));

        Thread worker = new Thread(() -> {
            try {
                subAgentCallService.call(
                        "j2agent-qa-assistant",
                        "routing",
                        new UniversalSubAgentCallService.SubAgentCallRequest(
                                "ctx-1", "turn-1", "user-1", "user-1:ctx-1:universal_assistant", null, List.of(),
                                userContext("zh_CN")));
            } catch (TurnCancelledException ignored) {
                // expected
            }
        });
        worker.start();
        try {
            Thread.sleep(200);
            ChatTurnCancellationRegistry.cancel("turn-1");
            worker.join(5000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
        assertTrue(!worker.isAlive());
    }

    @Test
    void callWithBridgeAppendsStreamedContentOncePerDelta() {
        AiAgent wikiAgent = Mockito.mock(AiAgent.class);
        when(wikiAgent.getAgentId()).thenReturn("j2agent-qa-assistant");
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(wikiAgent));
        when(agentRouter.route("j2agent-qa-assistant")).thenReturn(wikiAgent);

        when(agentStreamSession.stream(Mockito.any()))
                .thenReturn(Flux.just(
                        new StreamingTextParts("我", null),
                        new StreamingTextParts("将", null),
                        new StreamingTextParts("回答", null)));

        StringBuilder streamedContent = new StringBuilder();
        AgentTurnStateMachine stateMachine = new AgentTurnStateMachine();
        Object turnLock = new Object();
        ChatCallback<AgentUiEventEnvelope> callback = new ChatCallback<>("sub-1");
        AtomicInteger wsDeltaCount = new AtomicInteger(0);
        callback.responseCall = envelope -> wsDeltaCount.incrementAndGet();

        SubAgentStreamBridge.bind("turn-1", new SubAgentStreamBridge.Target(
                callback,
                "ctx-1",
                "turn-1",
                "user-1",
                "user-1:ctx-1:universal_assistant",
                null,
                new AtomicLong(0),
                stateMachine,
                turnLock,
                streamedContent,
                new StringBuilder(),
                new Object(),
                0));

        String result = subAgentCallService.call(
                "j2agent-qa-assistant",
                "查文档",
                new UniversalSubAgentCallService.SubAgentCallRequest(
                        "ctx-1", "turn-1", "user-1", "user-1:ctx-1:universal_assistant", null, List.of(),
                        userContext("zh_CN")));

        assertEquals("我将回答", result);
        assertEquals("我将回答", streamedContent.toString());
        assertEquals(3, wsDeltaCount.get());
    }

    private static UserContextBo userContext(String language) {
        UserContextBo userContext = new UserContextBo();
        userContext.setUserId("user-1");
        userContext.setLanguage(language);
        return userContext;
    }
}
