package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import io.github.jerryt92.j2agent.service.llm.chat.TurnCancelledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UniversalAssistantOrchestratorServiceTest {

    @AfterEach
    void tearDown() {
        ChatTurnCancellationRegistry.clear("turn-1");
    }

    @Test
    void returnsContinueWhenNoCandidates() {
        UniversalIntentQueryService intentQueryService = Mockito.mock(UniversalIntentQueryService.class);
        UniversalOrchestrationDecisionService orchestrationDecisionService =
                Mockito.mock(UniversalOrchestrationDecisionService.class);
        UniversalSubAgentCallService subAgentCallService = Mockito.mock(UniversalSubAgentCallService.class);
        AgentRouter agentRouter = Mockito.mock(AgentRouter.class);
        ChatMemory chatMemory = Mockito.mock(ChatMemory.class);
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(Mockito.mock(AiAgent.class)));
        when(intentQueryService.buildRoutingQuery(
                ArgumentMatchers.eq(chatMemory), anyString(), anyList(), anyString())).thenReturn("routing");
        when(intentQueryService.queryIntentAgents(anyString(), eq("routing"), any())).thenReturn("[]");

        UniversalAssistantOrchestratorService service = new UniversalAssistantOrchestratorService(
                intentQueryService, orchestrationDecisionService, subAgentCallService, agentRouter, chatMemory);

        UniversalAssistantOrchestratorService.OrchestrationOutcome outcome = service.orchestrate(request("hello"));

        assertEquals(UniversalAssistantOrchestratorService.OrchestrationOutcome.CONTINUE, outcome);
        verify(orchestrationDecisionService, never()).decide(anyString(), anyString(), anyList(), anySet(), anyBoolean(), any(), any());
        verify(subAgentCallService, never()).call(anyString(), anyString(), any());
    }

    @Test
    void returnsOrchestratedWhenSubAgentCalled() {
        UniversalIntentQueryService intentQueryService = Mockito.mock(UniversalIntentQueryService.class);
        UniversalOrchestrationDecisionService orchestrationDecisionService =
                Mockito.mock(UniversalOrchestrationDecisionService.class);
        UniversalSubAgentCallService subAgentCallService = Mockito.mock(UniversalSubAgentCallService.class);
        AgentRouter agentRouter = Mockito.mock(AgentRouter.class);
        ChatMemory chatMemory = Mockito.mock(ChatMemory.class);
        ChatAttachmentDto attachment = new ChatAttachmentDto().objectKey("chat/u/c/a.png").name("a.png");
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(Mockito.mock(AiAgent.class)));
        when(intentQueryService.buildRoutingQuery(
                ArgumentMatchers.eq(chatMemory), anyString(), anyList(), anyString())).thenReturn("routing");
        when(intentQueryService.queryIntentAgents(anyString(), anyString(), any())).thenReturn("[{\"agentId\":\"wiki\"}]");
        when(orchestrationDecisionService.decide(anyString(), anyString(), anyList(), anySet(), anyBoolean(), any(), any()))
                .thenReturn(UniversalOrchestrationDecisionService.OrchestrationDecision.invoke("wiki", null, "ok"))
                .thenReturn(UniversalOrchestrationDecisionService.OrchestrationDecision.complete("done"));
        when(subAgentCallService.call(anyString(), anyString(), any())).thenReturn("answer");

        UniversalAssistantOrchestratorService service = new UniversalAssistantOrchestratorService(
                intentQueryService, orchestrationDecisionService, subAgentCallService, agentRouter, chatMemory);

        UniversalAssistantOrchestratorService.OrchestrationOutcome outcome = service.orchestrate(
                new UniversalAssistantOrchestratorService.OrchestrationRequest(
                        "ctx-1",
                        "turn-1",
                        "user-1",
                        "user-1:ctx-1:universal_assistant",
                        null,
                        List.of(attachment),
                        "用户原问题",
                        null,
                        userContext("zh_CN")));

        assertEquals(UniversalAssistantOrchestratorService.OrchestrationOutcome.ORCHESTRATED, outcome);

        ArgumentCaptor<UniversalSubAgentCallService.SubAgentCallRequest> requestCaptor =
                ArgumentCaptor.forClass(UniversalSubAgentCallService.SubAgentCallRequest.class);
        verify(subAgentCallService).call(eq("wiki"), eq("routing"), requestCaptor.capture());
        assertEquals(1, requestCaptor.getValue().attachments().size());
        assertEquals("chat/u/c/a.png", requestCaptor.getValue().attachments().get(0).getObjectKey());
    }

    @Test
    void manualOrchestrateCallsTargetWithoutRecallOrDecision() {
        UniversalIntentQueryService intentQueryService = Mockito.mock(UniversalIntentQueryService.class);
        UniversalOrchestrationDecisionService orchestrationDecisionService =
                Mockito.mock(UniversalOrchestrationDecisionService.class);
        UniversalSubAgentCallService subAgentCallService = Mockito.mock(UniversalSubAgentCallService.class);
        AgentRouter agentRouter = Mockito.mock(AgentRouter.class);
        ChatMemory chatMemory = Mockito.mock(ChatMemory.class);
        AiAgent wikiAgent = Mockito.mock(AiAgent.class);
        when(wikiAgent.getAgentId()).thenReturn("wiki");
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(wikiAgent));
        when(intentQueryService.buildRoutingQuery(
                ArgumentMatchers.eq(chatMemory), anyString(), anyList(), anyString())).thenReturn("routing");
        when(subAgentCallService.call(anyString(), anyString(), any())).thenReturn("answer");

        UniversalAssistantOrchestratorService service = new UniversalAssistantOrchestratorService(
                intentQueryService, orchestrationDecisionService, subAgentCallService, agentRouter, chatMemory);

        UniversalAssistantOrchestratorService.OrchestrationOutcome outcome = service.orchestrate(
                new UniversalAssistantOrchestratorService.OrchestrationRequest(
                        "ctx-1",
                        "turn-1",
                        "user-1",
                        "user-1:ctx-1:universal_assistant",
                        null,
                        List.of(),
                        "用户原问题",
                        "wiki",
                        userContext("zh_CN")));

        assertEquals(UniversalAssistantOrchestratorService.OrchestrationOutcome.ORCHESTRATED, outcome);
        verify(intentQueryService, never()).queryIntentAgents(anyString(), anyString(), any());
        verify(orchestrationDecisionService, never()).decide(anyString(), anyString(), anyList(), anySet(), anyBoolean(), any(), any());
        verify(subAgentCallService).call(eq("wiki"), eq("routing"), any());
    }

    @Test
    void manualOrchestrateRejectsUnknownAgent() {
        UniversalIntentQueryService intentQueryService = Mockito.mock(UniversalIntentQueryService.class);
        UniversalOrchestrationDecisionService orchestrationDecisionService =
                Mockito.mock(UniversalOrchestrationDecisionService.class);
        UniversalSubAgentCallService subAgentCallService = Mockito.mock(UniversalSubAgentCallService.class);
        AgentRouter agentRouter = Mockito.mock(AgentRouter.class);
        ChatMemory chatMemory = Mockito.mock(ChatMemory.class);
        AiAgent wikiAgent = Mockito.mock(AiAgent.class);
        when(wikiAgent.getAgentId()).thenReturn("wiki");
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(wikiAgent));
        when(intentQueryService.buildRoutingQuery(
                ArgumentMatchers.eq(chatMemory), anyString(), anyList(), anyString())).thenReturn("routing");

        UniversalAssistantOrchestratorService service = new UniversalAssistantOrchestratorService(
                intentQueryService, orchestrationDecisionService, subAgentCallService, agentRouter, chatMemory);

        assertThrows(IllegalArgumentException.class, () -> service.orchestrate(
                new UniversalAssistantOrchestratorService.OrchestrationRequest(
                        "ctx-1",
                        "turn-1",
                        "user-1",
                        "user-1:ctx-1:universal_assistant",
                        null,
                        List.of(),
                        "用户原问题",
                        "missing",
                        userContext("zh_CN"))));

        verify(intentQueryService, never()).queryIntentAgents(anyString(), anyString(), any());
        verify(orchestrationDecisionService, never()).decide(anyString(), anyString(), anyList(), anySet(), anyBoolean(), any(), any());
        verify(subAgentCallService, never()).call(anyString(), anyString(), any());
    }

    @Test
    void throwsWhenCancelledBeforeOrchestration() {
        ChatTurnCancellationRegistry.cancel("turn-1");

        UniversalIntentQueryService intentQueryService = Mockito.mock(UniversalIntentQueryService.class);
        UniversalOrchestrationDecisionService orchestrationDecisionService =
                Mockito.mock(UniversalOrchestrationDecisionService.class);
        UniversalSubAgentCallService subAgentCallService = Mockito.mock(UniversalSubAgentCallService.class);
        AgentRouter agentRouter = Mockito.mock(AgentRouter.class);
        ChatMemory chatMemory = Mockito.mock(ChatMemory.class);
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(Mockito.mock(AiAgent.class)));

        UniversalAssistantOrchestratorService service = new UniversalAssistantOrchestratorService(
                intentQueryService, orchestrationDecisionService, subAgentCallService, agentRouter, chatMemory);

        assertThrows(TurnCancelledException.class, () -> service.orchestrate(request("hello")));

        verify(intentQueryService, never()).queryIntentAgents(anyString(), anyString(), any());
        verify(subAgentCallService, never()).call(anyString(), anyString(), any());
    }

    @Test
    void returnsContinueWhenNoCallableSubAgents() {
        AgentRouter agentRouter = Mockito.mock(AgentRouter.class);
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of());

        UniversalAssistantOrchestratorService service = new UniversalAssistantOrchestratorService(
                Mockito.mock(UniversalIntentQueryService.class),
                Mockito.mock(UniversalOrchestrationDecisionService.class),
                Mockito.mock(UniversalSubAgentCallService.class),
                agentRouter,
                Mockito.mock(ChatMemory.class));

        assertEquals(
                UniversalAssistantOrchestratorService.OrchestrationOutcome.CONTINUE,
                service.orchestrate(request("hello")));
    }

    private static UniversalAssistantOrchestratorService.OrchestrationRequest request(String userMessage) {
        return new UniversalAssistantOrchestratorService.OrchestrationRequest(
                "ctx-1",
                "turn-1",
                "user-1",
                "user-1:ctx-1:universal_assistant",
                null,
                List.of(),
                userMessage,
                null,
                userContext("zh_CN"));
    }

    private static UserContextBo userContext(String language) {
        UserContextBo userContext = new UserContextBo();
        userContext.setUserId("user-1");
        userContext.setLanguage(language);
        return userContext;
    }
}
