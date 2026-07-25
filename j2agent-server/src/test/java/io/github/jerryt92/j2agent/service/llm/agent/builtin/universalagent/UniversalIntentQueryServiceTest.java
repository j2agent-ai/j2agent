package io.github.jerryt92.j2agent.service.llm.agent.builtin.universalagent;

import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.I18nString;
import io.github.jerryt92.j2agent.service.llm.LlmSyncService;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import io.github.jerryt92.j2agent.service.llm.chat.TurnCancelledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class UniversalIntentQueryServiceTest {

    @AfterEach
    void tearDown() {
        ChatTurnCancellationRegistry.clear("turn-1");
    }

    @Test
    void queryIntentAgentsThrowsWhenCancelledBeforeLlm() {
        UniversalIntentQueryService service = new UniversalIntentQueryService(null, null);
        ChatTurnCancellationRegistry.cancel("turn-1");
        assertThrows(TurnCancelledException.class,
                () -> service.queryIntentAgents("conv", "question", "turn-1"));
    }

    @Test
    void queryIntentAgentsThrowsWhenCancelledAfterLlmReturns() {
        AgentRouter router = Mockito.mock(AgentRouter.class);
        LlmSyncService llmSyncService = Mockito.mock(LlmSyncService.class);
        AiAgent agent = new StubAgent("a1", "Name", "desc", "orchestration");
        when(router.listCallableSubAgents()).thenReturn(List.of(agent));
        when(llmSyncService.callAssistantText(any(Prompt.class))).thenAnswer(invocation -> {
            ChatTurnCancellationRegistry.cancel("turn-1");
            return "[]";
        });
        UniversalIntentQueryService service = new UniversalIntentQueryService(router, llmSyncService);
        assertThrows(TurnCancelledException.class,
                () -> service.queryIntentAgents("conv", "question", "turn-1"));
    }

    @Test
    void sanitizeCandidateJsonFiltersUnknownAgents() {
        AiAgent reportAgent = Mockito.mock(AiAgent.class);
        Mockito.when(reportAgent.getAgentId()).thenReturn("chat_assistant");
        Mockito.when(reportAgent.getAgentName()).thenReturn(new I18nString()
                .zhCN("智能报告")
                .enUS("Intelligent Report"));

        String raw = """
                [
                  {"agentId":"chat_assistant","name":"智能报告","relevance":"high","reason":"写报告"},
                  {"agentId":"unknown_agent","name":"X","relevance":"high","reason":"bad"}
                ]
                """;

        String json = UniversalIntentQueryService.sanitizeCandidateJson(raw, List.of(reportAgent));
        assertTrue(json.contains("chat_assistant"));
        assertTrue(json.contains("high"));
        assertTrue(!json.contains("unknown_agent"));
    }

    @Test
    void sanitizeCandidateJsonReturnsEmptyArrayOnInvalidInput() {
        AiAgent agent = Mockito.mock(AiAgent.class);
        Mockito.when(agent.getAgentId()).thenReturn("a1");

        assertEquals("[]", UniversalIntentQueryService.sanitizeCandidateJson("", List.of(agent)));
        assertEquals("[]", UniversalIntentQueryService.sanitizeCandidateJson("not json", List.of(agent)));
    }

    @Test
    void formatCandidateBlockUsesOrchestrationPromptWhenPresent() {
        AiAgent agent = new StubAgent("a1", "Name", "Public desc", "Internal orchestration hint");
        String block = UniversalIntentQueryService.formatCandidateBlock(List.of(agent));
        assertTrue(block.contains("【a1】"));
        assertTrue(block.contains("orchestrationPrompt: Internal orchestration hint"));
        assertTrue(!block.contains("Public desc"));
    }

    @Test
    void formatCandidateBlockFallsBackToDescriptionWhenOrchestrationBlank() {
        AiAgent agent = new StubAgent("a1", "Name", "Public desc", null);
        String block = UniversalIntentQueryService.formatCandidateBlock(List.of(agent));
        assertTrue(block.contains("orchestrationPrompt: Public desc"));
    }

    @Test
    void sanitizeCandidateJsonIncludesOrchestrationPromptInResult() {
        AiAgent agent = new StubAgent("chat_assistant", "智能报表", "desc", "SQL 报表编排提示");
        String raw = """
                [{"agentId":"chat_assistant","name":"智能报表","relevance":"high","reason":"占比统计"}]
                """;
        String json = UniversalIntentQueryService.sanitizeCandidateJson(raw, List.of(agent));
        assertTrue(json.contains("orchestrationPrompt"));
        assertTrue(json.contains("SQL 报表编排提示"));
    }

    @Test
    void buildRoutingQueryWithoutHistoryUsesLatestMessageOnly() {
        UniversalIntentQueryService service = new UniversalIntentQueryService(null, null);
        String query = service.buildRoutingQuery(null, "conv-1", "  hello  ");
        assertEquals("【本轮问题】\nhello", query);
    }

    @Test
    void buildRoutingQueryIncludesRecentDialogueFromMemory() {
        ChatMemory memory = Mockito.mock(ChatMemory.class);
        when(memory.get("conv-1")).thenReturn(List.of(
                new UserMessage("上一轮问题"),
                new AssistantMessage("上一轮回答"),
                new UserMessage("更早问题"),
                AssistantMessage.builder().toolCalls(List.of()).content("").build()));
        UniversalIntentQueryService service = new UniversalIntentQueryService(null, null);
        String query = service.buildRoutingQuery(memory, "conv-1", "本轮问题");
        assertTrue(query.contains("【对话上下文】"));
        assertTrue(query.contains("用户: 上一轮问题"));
        assertTrue(query.contains("助手: 上一轮回答"));
        assertTrue(query.contains("【本轮问题】\n本轮问题"));
    }

    @Test
    void buildRoutingQueryFromMessagesIncludesTraceAndLatestUser() {
        UniversalIntentQueryService service = new UniversalIntentQueryService(null, null);
        String query = service.buildRoutingQueryFromMessages(
                List.of(new UserMessage("上一轮"), new AssistantMessage("回答"), new UserMessage("本轮")),
                "【本回合已执行子智能体调用】\n- agentId: wiki");
        assertTrue(query.contains("用户: 上一轮"));
        assertTrue(query.contains("【本回合已执行子智能体调用】"));
        assertTrue(query.contains("【本轮问题】\n本轮"));
    }

    @Test
    void buildRoutingQueryWithMemoryDedupesPrePersistedLatestUser() {
        ChatMemory memory = Mockito.mock(ChatMemory.class);
        when(memory.get("conv-1")).thenReturn(List.of(
                new UserMessage("上一轮问题"),
                new AssistantMessage("上一轮回答"),
                new UserMessage("本轮问题")));
        UniversalIntentQueryService service = new UniversalIntentQueryService(null, null);
        String query = service.buildRoutingQuery(
                memory,
                "conv-1",
                List.of(new UserMessage("本轮问题")),
                "【本回合已执行子智能体调用】\n- agentId: wiki");
        assertTrue(query.contains("用户: 上一轮问题"));
        assertTrue(query.contains("助手: 上一轮回答"));
        assertTrue(!query.contains("用户: 本轮问题"));
        assertTrue(query.contains("【本回合已执行子智能体调用】"));
        assertTrue(query.contains("【本轮问题】\n本轮问题"));
    }

    @Test
    void dedupeTrailingUserLineRemovesMatchingLastLine() {
        List<String> deduped = UniversalIntentQueryService.dedupeTrailingUserLine(
                List.of("用户: 上一轮", "用户: 本轮"), "本轮");
        assertEquals(1, deduped.size());
        assertEquals("用户: 上一轮", deduped.get(0));
    }

    @Test
    void buildRoutingQueryImageOnlyUsesAttachmentMarker() {
        ChatAttachmentDto attachment = new ChatAttachmentDto().objectKey("chat/u/c/a.png").name("a.png");
        UserMessage userMessage = UserMessage.builder()
                .text("")
                .metadata(Map.of("attachments", List.of(attachment)))
                .build();
        UniversalIntentQueryService service = new UniversalIntentQueryService(null, null);
        String query = service.buildRoutingQuery(null, null, List.of(userMessage), "");
        assertTrue(query.contains("【本轮问题】\n（无文字，含图片附件）"));
    }

    @Test
    void resolveLatestAttachmentsFromMessageMetadata() {
        ChatAttachmentDto attachment = new ChatAttachmentDto().objectKey("chat/u/c/a.png").name("a.png");
        UserMessage userMessage = UserMessage.builder()
                .text("")
                .metadata(Map.of("attachments", List.of(attachment)))
                .build();
        List<ChatAttachmentDto> resolved = UniversalIntentQueryService.resolveLatestAttachments(
                List.of(userMessage), null, "conv-1");
        assertEquals(1, resolved.size());
        assertEquals("chat/u/c/a.png", resolved.get(0).getObjectKey());
    }

    @Test
    void extractRecentDialogueLinesIncludesImageOnlyUserMarker() {
        ChatAttachmentDto attachment = new ChatAttachmentDto().objectKey("chat/u/c/a.png").name("a.png");
        UserMessage userMessage = UserMessage.builder()
                .text("")
                .metadata(Map.of("attachments", List.of(attachment)))
                .build();
        List<String> lines = UniversalIntentQueryService.extractRecentDialogueLines(List.of(userMessage), 3);
        assertEquals(1, lines.size());
        assertEquals(UniversalIntentQueryService.IMAGE_ONLY_DIALOGUE_LINE, lines.get(0));
    }

    @Test
    void extractRecentDialogueLinesSkipsToolCallAssistantMessages() {
        List<String> lines = UniversalIntentQueryService.extractRecentDialogueLines(List.of(
                new UserMessage("u1"),
                AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "call_sub_agent", "{}")))
                        .content("")
                        .build(),
                new AssistantMessage("a1")), 3);
        assertEquals(2, lines.size());
        assertEquals("用户: u1", lines.get(0));
        assertEquals("助手: a1", lines.get(1));
    }

    private static final class StubAgent extends AiAgent {
        private final String id;
        private final String name;
        private final String description;
        private final String orchestrationPrompt;

        private StubAgent(String id, String name, String description, String orchestrationPrompt) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.orchestrationPrompt = orchestrationPrompt;
        }

        @Override
        public String getAgentId() {
            return id;
        }

        @Override
        public I18nString getAgentName() {
            return new I18nString().zhCN(name).enUS(name);
        }

        @Override
        public I18nString getAgentDescription() {
            return new I18nString().zhCN(description).enUS(description);
        }

        @Override
        public String getOrchestrationPrompt() {
            return orchestrationPrompt;
        }

        @Override
        public String loadSystemPrompt() {
            return "";
        }
    }
}
