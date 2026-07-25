package io.github.jerryt92.j2agent.service.llm.agent.builtin.universalagent;

import io.github.jerryt92.j2agent.service.llm.LlmSyncService;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import io.github.jerryt92.j2agent.service.llm.chat.TurnCancelledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class UniversalOrchestrationDecisionServiceTest {

    @AfterEach
    void tearDown() {
        ChatTurnCancellationRegistry.clear("turn-1");
    }

    @Test
    void decideThrowsWhenCancelledAfterLlmReturns() {
        LlmSyncService llmSyncService = Mockito.mock(LlmSyncService.class);
        when(llmSyncService.callAssistantText(any(Prompt.class))).thenAnswer(invocation -> {
            ChatTurnCancellationRegistry.cancel("turn-1");
            return "{\"action\":\"complete\",\"reason\":\"ok\"}";
        });
        UniversalOrchestrationDecisionService service = new UniversalOrchestrationDecisionService(llmSyncService);
        assertThrows(TurnCancelledException.class, () -> service.decide(
                "[{\"agentId\":\"wiki\"}]",
                "routing",
                List.of(),
                Set.of(),
                false,
                "turn-1"));
    }

    @Test
    void parseInvokeDecisionWithoutQuery() {
        String raw = """
                {"action":"invoke","agentId":"wiki","reason":"需要知识库"}
                """;
        UniversalOrchestrationDecisionService.OrchestrationDecision decision =
                UniversalOrchestrationDecisionService.parseDecision(raw);
        assertTrue(decision.isInvoke());
        assertEquals("wiki", decision.agentId());
        assertEquals(null, decision.query());
    }

    @Test
    void parseInvokeDecision() {
        String raw = """
                {"action":"invoke","agentId":"wiki","query":"查文档","reason":"需要知识库"}
                """;
        UniversalOrchestrationDecisionService.OrchestrationDecision decision =
                UniversalOrchestrationDecisionService.parseDecision(raw);
        assertTrue(decision.isInvoke());
        assertEquals("wiki", decision.agentId());
        assertEquals("查文档", decision.query());
    }

    @Test
    void parseCompleteDecision() {
        String raw = """
                {"action":"complete","reason":"无需子智能体"}
                """;
        UniversalOrchestrationDecisionService.OrchestrationDecision decision =
                UniversalOrchestrationDecisionService.parseDecision(raw);
        assertTrue(decision.isComplete());
    }

    @Test
    void parseInvalidFallsBackToComplete() {
        UniversalOrchestrationDecisionService.OrchestrationDecision decision =
                UniversalOrchestrationDecisionService.parseDecision("not json");
        assertTrue(decision.isComplete());
    }

    @Test
    void isCandidatesEmpty() {
        assertTrue(UniversalIntentQueryService.isCandidatesEmpty("[]"));
        assertTrue(UniversalIntentQueryService.isCandidatesEmpty("  "));
        assertFalse(UniversalIntentQueryService.isCandidatesEmpty("[{\"agentId\":\"a1\"}]"));
    }
}
