package io.github.jerryt92.j2agent.service.llm.agent.inf;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.skills.SkillsInterceptor;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.I18nString;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentService;
import io.github.jerryt92.j2agent.service.llm.advisor.ReactCompatibleMessageChatMemoryAdvisor;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunContext;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(20)
class AiAgentReactHistoryTest {

    private static final String CONVERSATION_ID = "user:ctx:history_agent";
    private static final String CHILD_CONVERSATION_ID = "user:ctx:child_agent";
    private static final String SYSTEM_PROMPT = "Keep the user's earlier constraints.";

    @AfterEach
    void clearAdvisorContext() {
        ReactCompatibleMessageChatMemoryAdvisor.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void historySurvivesTwoToolCallsAndNextTurnWithoutDuplicateMessages(boolean subAgentCallRun) throws Exception {
        RecordingMemory memory = new RecordingMemory(subAgentCallRun ? CHILD_CONVERSATION_ID : CONVERSATION_ID);
        memory.seed(new UserMessage("Only paid orders; report amounts in CNY."),
                new AssistantMessage("Understood."), new UserMessage("Continue last month."));
        List<Message> initialHistory = List.copyOf(memory.messages);
        ScriptedModel model = new ScriptedModel(toolCall("call-1", "lookup"),
                toolCall("call-2", "lookup"), new AssistantMessage("Done."), new AssistantMessage("Next."));
        AtomicInteger toolCalls = new AtomicInteger();
        ToolCallback tool = FunctionToolCallback.builder("lookup", () -> "result-" + toolCalls.incrementAndGet())
                .description("Look up orders").build();
        TestAgent agent = new TestAgent(memory, model, List.of(tool));

        Flux<?> stream = agent.stream(context("Continue last month.", true, subAgentCallRun));
        assertEquals(0, memory.reads, "History must be loaded on subscription");
        stream.blockLast(Duration.ofSeconds(10));

        assertEquals(1, memory.reads);
        assertEquals(2, toolCalls.get());
        assertEquals(3, model.prompts.size());
        assertEquals(List.of(SYSTEM_PROMPT, "Only paid orders; report amounts in CNY.",
                        "Understood.", "Continue last month."), texts(model.prompts.getFirst()));
        assertPrefix(model.prompts.get(0), model.prompts.get(1));
        assertPrefix(model.prompts.get(1), model.prompts.get(2));
        assertEquals(2, memory.messages.stream().filter(ToolResponseMessage.class::isInstance).count());
        assertEquals(1, memory.messages.stream().filter(UserMessage.class::isInstance)
                .filter(m -> "Continue last month.".equals(m.getText())).count());
        assertEquals(initialHistory, memory.messages.subList(0, initialHistory.size()));

        agent.stream(context("Next question.", false, subAgentCallRun)).blockLast(Duration.ofSeconds(10));

        assertEquals(2, memory.reads);
        assertPrefix(model.prompts.get(2), model.prompts.get(3));
        assertEquals("Done.", model.prompts.get(3).get(model.prompts.get(2).size()).getText());
        assertEquals("Next question.", model.prompts.get(3).getLast().getText());
        assertEquals(2, model.prompts.get(3).stream().filter(ToolResponseMessage.class::isInstance).count());
    }

    @Test
    void emptyHistoryAddsCurrentUserOnce() throws Exception {
        RecordingMemory memory = new RecordingMemory();
        ScriptedModel model = new ScriptedModel(new AssistantMessage("Done."));
        TestAgent agent = new TestAgent(memory, model, List.of());

        agent.stream(context("Hello.", false, false)).blockLast(Duration.ofSeconds(10));

        assertEquals(List.of(SYSTEM_PROMPT, "Hello."), texts(model.prompts.getFirst()));
        assertEquals(1, memory.reads);
        assertEquals(1, memory.messages.stream().filter(UserMessage.class::isInstance).count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void identicalTextInDifferentTurnsIsNotDeduplicated(boolean prePersisted) throws Exception {
        RecordingMemory memory = new RecordingMemory();
        memory.seed(new UserMessage("Again."), new AssistantMessage("Earlier answer."));
        if (prePersisted) {
            memory.seed(new UserMessage("Again."));
        }
        ScriptedModel model = new ScriptedModel(new AssistantMessage("New answer."));
        TestAgent agent = new TestAgent(memory, model, List.of());

        agent.stream(context("Again.", prePersisted, false)).blockLast(Duration.ofSeconds(10));

        assertEquals(List.of(SYSTEM_PROMPT, "Again.", "Earlier answer.", "Again."), texts(model.prompts.getFirst()));
        assertEquals(2, memory.messages.stream().filter(UserMessage.class::isInstance).count());
    }

    @Test
    void prePersistedUserDoesNotReplaceEarlierTurnWhenHistoryEndsWithAssistant() throws Exception {
        RecordingMemory memory = new RecordingMemory();
        memory.seed(new UserMessage("Earlier."), new AssistantMessage("Earlier answer."));
        ScriptedModel model = new ScriptedModel(new AssistantMessage("Done."));
        TestAgent agent = new TestAgent(memory, model, List.of());

        agent.stream(context("Current.", true, false)).blockLast(Duration.ofSeconds(10));

        assertEquals(List.of(SYSTEM_PROMPT, "Earlier.", "Earlier answer.", "Current."),
                texts(model.prompts.getFirst()));
    }

    @Test
    void prePersistedUserWithEmptyHistoryStillReachesGraph() throws Exception {
        RecordingMemory memory = new RecordingMemory();
        ScriptedModel model = new ScriptedModel(new AssistantMessage("Done."));
        TestAgent agent = new TestAgent(memory, model, List.of());

        agent.stream(context("Current.", true, false)).blockLast(Duration.ofSeconds(10));

        assertEquals(List.of(SYSTEM_PROMPT, "Current."), texts(model.prompts.getFirst()));
    }

    @Test
    void prePersistedUserKeepsLiveMediaAndMetadata() throws Exception {
        RecordingMemory memory = new RecordingMemory();
        memory.seed(new UserMessage("Describe this."));
        ScriptedModel model = new ScriptedModel(new AssistantMessage("Done."));
        TestAgent agent = new TestAgent(memory, model, List.of());
        ChatAttachmentDto attachment = new ChatAttachmentDto();
        attachment.setObjectKey("test/image.png");
        attachment.setContentType("image/png");
        Media media = Media.builder().mimeType(MediaType.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1, 2, 3})).build();
        ChatAttachmentService attachments = mock(ChatAttachmentService.class);
        when(attachments.toMedia(List.of(attachment))).thenReturn(List.of(media));
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatAttachmentService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(attachments);
        ReflectionTestUtils.setField(agent, "chatAttachmentServiceProvider", provider);
        AgentRunContext base = context("Describe this.", true, false);
        AgentRunContext input = new AgentRunContext(base.text(), base.contextId(), base.userId(), base.turnId(),
                base.conversationId(), base.agentId(), base.userContext(), List.of(attachment),
                List.of("knowledge"), null, false, true);

        agent.stream(input).blockLast(Duration.ofSeconds(10));

        UserMessage user = assertInstanceOf(UserMessage.class, model.prompts.getFirst().getLast());
        assertEquals(List.of(media), user.getMedia());
        assertEquals(CONVERSATION_ID, user.getMetadata().get(ChatMemory.CONVERSATION_ID));
        assertEquals(List.of(attachment), user.getMetadata().get("attachments"));
        assertEquals(Boolean.TRUE, user.getMetadata().get(
                ReactCompatibleMessageChatMemoryAdvisor.META_GRAPH_HISTORY_PREPARED));
        assertEquals(1, model.prompts.getFirst().stream().filter(UserMessage.class::isInstance).count());
    }

    @Test
    void skillsSystemAndAgentSystemBothSurviveEveryModelCall() throws Exception {
        RecordingMemory memory = new RecordingMemory();
        memory.seed(new UserMessage("Earlier constraint."), new AssistantMessage("Understood."),
                new UserMessage("Current."));
        ScriptedModel model = new ScriptedModel(toolCall("call-1", "lookup"), new AssistantMessage("Done."));
        SkillRegistry registry = mock(SkillRegistry.class, RETURNS_DEEP_STUBS);
        when(registry.listAll()).thenReturn(List.of(SkillMetadata.builder().name("test-skill")
                .description("Test skill").skillPath("/test/skills/test-skill").build()));
        when(registry.getSystemPromptTemplate().render(anyMap())).thenReturn("Skills system.");
        SkillsInterceptor skills = SkillsInterceptor.builder().skillRegistry(registry).build();
        TestAgent agent = new TestAgent(memory, model, List.of(
                FunctionToolCallback.builder("lookup", () -> "result").description("Look up").build()), skills);

        agent.stream(context("Current.", true, false)).blockLast(Duration.ofSeconds(10));

        assertEquals(2, model.prompts.size());
        for (List<Message> prompt : model.prompts) {
            assertEquals(List.of("Skills system.", SYSTEM_PROMPT), prompt.stream()
                    .filter(SystemMessage.class::isInstance).map(Message::getText).toList());
        }
        assertPrefix(model.prompts.get(0), model.prompts.get(1));
    }

    @Test
    void parentAndChildKeepTheirOwnHistoryAcrossDelegatedAndDirectCalls() throws Exception {
        RecordingMemory childMemory = new RecordingMemory(CHILD_CONVERSATION_ID);
        childMemory.seed(new UserMessage("Child's earlier constraint."), new AssistantMessage("Understood."));
        ScriptedModel childModel = new ScriptedModel(toolCall("child-call", "lookup"),
                new AssistantMessage("Delegated answer."), new AssistantMessage("Child follow-up answer."));
        TestAgent child = new TestAgent(childMemory, childModel, List.of(
                FunctionToolCallback.builder("lookup", () -> "child result").description("Look up").build()));
        RecordingMemory parentMemory = new RecordingMemory();
        parentMemory.seed(new UserMessage("Only paid orders."), new AssistantMessage("Understood."),
                new UserMessage("Continue."));
        ScriptedModel parentModel = new ScriptedModel(toolCall("parent-call", "sub_agent_call"),
                new AssistantMessage("Parent answer."));
        ToolCallback delegate = FunctionToolCallback.builder("sub_agent_call", () -> {
            try {
                child.stream(context("Delegated task.", false, true)).blockLast(Duration.ofSeconds(10));
                return "Delegated answer.";
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }).description("Delegate to a specialist").build();
        TestAgent parent = new TestAgent(parentMemory, parentModel, List.of(delegate));

        parent.stream(context("Continue.", true, false)).blockLast(Duration.ofSeconds(10));

        assertEquals(2, parentModel.prompts.size());
        assertPrefix(parentModel.prompts.get(0), parentModel.prompts.get(1));
        assertTrue(texts(parentModel.prompts.get(1)).contains("Only paid orders."));
        assertEquals(1, parentMemory.reads);
        assertEquals(1, parentMemory.messages.stream().filter(ToolResponseMessage.class::isInstance).count());
        assertEquals(2, childModel.prompts.size());
        assertEquals(List.of(SYSTEM_PROMPT, "Child's earlier constraint.", "Understood.", "Delegated task."),
                texts(childModel.prompts.getFirst()));
        assertPrefix(childModel.prompts.get(0), childModel.prompts.get(1));
        assertEquals(1, childMemory.reads);
        assertEquals(1, childMemory.messages.stream().filter(ToolResponseMessage.class::isInstance).count());
        assertEquals("Delegated answer.", childMemory.messages.getLast().getText());
        assertTrue(parentModel.prompts.stream().noneMatch(prompt -> texts(prompt).contains("Child's earlier constraint.")));

        AgentRunContext delegated = context("Child follow-up.", false, true);
        AgentRunContext direct = new AgentRunContext(delegated.text(), delegated.contextId(), delegated.userId(),
                delegated.turnId(), delegated.conversationId(), delegated.agentId(), delegated.userContext(),
                List.of(), List.of(), null, false, false);
        child.stream(direct).blockLast(Duration.ofSeconds(10));

        assertEquals(2, childMemory.reads);
        assertPrefix(childModel.prompts.get(1), childModel.prompts.get(2));
        assertEquals(List.of("Delegated answer.", "Child follow-up."),
                texts(childModel.prompts.get(2)).subList(childModel.prompts.get(1).size(), childModel.prompts.get(2).size()));
        assertEquals("Child follow-up answer.", childMemory.messages.getLast().getText());
    }

    private static AgentRunContext context(String text, boolean prePersisted, boolean subAgent) {
        UserContextBo user = new UserContextBo();
        user.setUserId("user");
        user.setRole(UserRoleEnum.ADMIN);
        String agentId = subAgent ? "child_agent" : "history_agent";
        return new AgentRunContext(text, "ctx", "user", "turn-" + text,
                "user:ctx:" + agentId, agentId, user, List.of(), List.of(), null, subAgent, prePersisted);
    }

    private static AssistantMessage toolCall(String id, String name) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, "{}"))).build();
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }

    private static void assertPrefix(List<Message> previous, List<Message> next) {
        assertTrue(next.size() > previous.size());
        // Runtime routing flags are intentionally omitted by the persistence codec and are not model content.
        for (int i = 0; i < previous.size(); i++) {
            Message before = previous.get(i);
            Message after = next.get(i);
            assertEquals(before.getMessageType(), after.getMessageType(), "Message role at " + i);
            assertEquals(before.getText(), after.getText(), "Message text at " + i);
            if (before instanceof UserMessage user) {
                assertEquals(user.getMedia(), ((UserMessage) after).getMedia(), "Media at " + i);
            } else if (before instanceof AssistantMessage assistant) {
                assertEquals(assistant.getToolCalls(), ((AssistantMessage) after).getToolCalls(), "Tool calls at " + i);
            } else if (before instanceof ToolResponseMessage tool) {
                assertEquals(tool.getResponses(), ((ToolResponseMessage) after).getResponses(), "Tool results at " + i);
            }
        }
    }

    private static final class TestAgent extends AiAgent {
        private TestAgent(ChatMemory memory, ChatModel model, List<ToolCallback> tools, Interceptor... interceptors) {
            chatMemory = memory;
            agent = ReactAgent.builder().name("history_test")
                    .chatClient(ChatClient.builder(model).defaultAdvisors(
                            ReactCompatibleMessageChatMemoryAdvisor.builder(memory).build()).build())
                    .systemPrompt("").tools(tools).interceptors(interceptors).build();
        }

        @Override public String getAgentId() { return "history_agent"; }
        @Override public I18nString getAgentName() { return null; }
        @Override public I18nString getAgentDescription() { return null; }
        @Override public String loadSystemPrompt() { return SYSTEM_PROMPT; }
    }

    private static final class ScriptedModel implements ChatModel {
        private final Deque<AssistantMessage> responses;
        private final List<List<Message>> prompts = new ArrayList<>();

        private ScriptedModel(AssistantMessage... responses) {
            this.responses = new ArrayDeque<>(Arrays.asList(responses));
        }

        @Override public ChatResponse call(Prompt prompt) {
            throw new AssertionError("Expected streaming model call");
        }

        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> {
                prompts.add(List.copyOf(prompt.getInstructions()));
                return Flux.just(new ChatResponse(List.of(new Generation(responses.removeFirst()))));
            });
        }
    }

    /** Exercise the real persistence codec without a database; runtime metadata is not stored. */
    private static final class RecordingMemory implements ChatMemory {
        private final ChatMemoryMessageCodec codec = new ChatMemoryMessageCodec(new ObjectMapper());
        private final List<Message> messages = new ArrayList<>();
        private final String conversationId;
        private int reads;

        private RecordingMemory() {
            this(AiAgentReactHistoryTest.CONVERSATION_ID);
        }

        private RecordingMemory(String conversationId) {
            this.conversationId = conversationId;
        }

        private void seed(Message... messages) {
            add(conversationId, Arrays.asList(messages));
        }

        @Override public void add(String conversationId, List<Message> delta) {
            assertEquals(this.conversationId, conversationId, "Messages must be persisted under the agent's own key");
            for (Message message : delta) {
                try {
                    ChatMemoryMessageCodec.PersistedRow row = codec.encode(message);
                    if (row != null) {
                        Message decoded = codec.decode(row.chatRole(), row.content(), row.metaJson(), row.ragInfos());
                        if (decoded != null) {
                            messages.add(decoded);
                        }
                    }
                } catch (Exception ex) {
                    throw new AssertionError("Cannot persist test message", ex);
                }
            }
        }

        @Override public List<Message> get(String conversationId) {
            assertEquals(this.conversationId, conversationId, "History must be loaded under the agent's own key");
            reads++;
            return List.copyOf(messages);
        }

        @Override public void clear(String conversationId) {
            messages.clear();
        }
    }
}
