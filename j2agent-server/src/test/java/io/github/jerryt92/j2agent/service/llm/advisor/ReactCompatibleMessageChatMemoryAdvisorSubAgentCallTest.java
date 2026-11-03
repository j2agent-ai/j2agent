package io.github.jerryt92.j2agent.service.llm.advisor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;

import java.time.Duration;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactCompatibleMessageChatMemoryAdvisorSubAgentCallTest {

    @AfterEach
    void tearDown() {
        ReactCompatibleMessageChatMemoryAdvisor.clear();
    }

    @Test
    void subAgentCallRunReadsHistoryAndPersistsUser() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("user:ctx:rc_wiki_assistant")).thenReturn(List.of(
                new UserMessage("previous"), new AssistantMessage("previous answer")));
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        UserMessage user = UserMessage.builder().text("query")
                .metadata(Map.of(ChatMemory.CONVERSATION_ID, "user:ctx:rc_wiki_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_SUB_AGENT_CALL_RUN, true)).build();

        ChatClientRequest processed = advisor.before(ChatClientRequest.builder()
                .prompt(new Prompt(List.of(user))).build(), mock(AdvisorChain.class));

        assertEquals(List.of("previous", "previous answer", "query"),
                processed.prompt().getInstructions().stream().map(Message::getText).toList());
        verify(chatMemory).get("user:ctx:rc_wiki_assistant");
        verify(chatMemory).add("user:ctx:rc_wiki_assistant", user);
    }

    @Test
    void subAgentCallRunPersistsToolResponseAndAssistantWithoutUserInFollowUp() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("user:ctx:rc_wiki_assistant")).thenReturn(List.of());
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);
        UserMessage user = UserMessage.builder().text("query")
                .metadata(Map.of(ChatMemory.CONVERSATION_ID, "user:ctx:rc_wiki_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_SUB_AGENT_CALL_RUN, true)).build();
        advisor.before(ChatClientRequest.builder().prompt(new Prompt(List.of(user))).build(), chain);
        AssistantMessage call = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}"))).build();
        ToolResponseMessage result = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "lookup", "ok"))).build();

        advisor.before(ChatClientRequest.builder().prompt(new Prompt(List.of(call, result)))
                .context(Map.of(ChatMemory.CONVERSATION_ID, "user:ctx:rc_wiki_assistant")).build(), chain);
        AssistantMessage answer = new AssistantMessage("done");
        advisor.after(ChatClientResponse.builder().chatResponse(new ChatResponse(List.of(new Generation(answer))))
                .context(Map.of(ChatMemory.CONVERSATION_ID, "user:ctx:rc_wiki_assistant")).build(), chain);

        verify(chatMemory).get("user:ctx:rc_wiki_assistant");
        verify(chatMemory).add("user:ctx:rc_wiki_assistant", user);
        verify(chatMemory).add("user:ctx:rc_wiki_assistant", result);
        verify(chatMemory).add("user:ctx:rc_wiki_assistant", List.of(answer));
    }

    @Test
    void subAgentFlagOnlyInRequestContextDoesNotDisableMemory() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("user:ctx:rc_wiki_assistant")).thenReturn(List.of(new UserMessage("previous")));
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        UserMessage user = new UserMessage("query");
        ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt(List.of(user)))
                .context(Map.of(ChatMemory.CONVERSATION_ID, "user:ctx:rc_wiki_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_SUB_AGENT_CALL_RUN, true)).build();

        ChatClientRequest processed = advisor.before(request, mock(AdvisorChain.class));

        assertEquals(List.of("previous", "query"),
                processed.prompt().getInstructions().stream().map(Message::getText).toList());
        verify(chatMemory).get("user:ctx:rc_wiki_assistant");
        verify(chatMemory).add("user:ctx:rc_wiki_assistant", user);
    }

    @Test
    void legacyDelegateRunMetadataDoesNotDisableMemory() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("user:ctx:rc_wiki_assistant")).thenReturn(List.of(new UserMessage("previous")));
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        UserMessage user = UserMessage.builder().text("query")
                .metadata(Map.of(ChatMemory.CONVERSATION_ID, "user:ctx:rc_wiki_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_DELEGATE_RUN, true)).build();

        ChatClientRequest processed = advisor.before(ChatClientRequest.builder()
                .prompt(new Prompt(List.of(user))).build(), mock(AdvisorChain.class));

        assertEquals(List.of("previous", "query"),
                processed.prompt().getInstructions().stream().map(Message::getText).toList());
        verify(chatMemory).get("user:ctx:rc_wiki_assistant");
        verify(chatMemory).add("user:ctx:rc_wiki_assistant", user);
    }

    @Test
    void preparedGraphPreservesAllSystemMessagesWithoutReadingMemory() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        UserMessage currentUser = UserMessage.builder().text("current")
                .metadata(Map.of(ChatMemory.CONVERSATION_ID, "user:ctx:universal_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_GRAPH_HISTORY_PREPARED, true,
                        ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, true))
                .build();
        List<Message> messages = List.of(new SystemMessage("skills"), new SystemMessage("agent"), currentUser);

        ChatClientRequest processed = advisor.before(ChatClientRequest.builder()
                .prompt(new Prompt(messages)).build(), mock(AdvisorChain.class));

        assertEquals(messages, processed.prompt().getInstructions());
        verify(chatMemory, never()).get(anyString());
        verify(chatMemory, never()).add(anyString(), any(Message.class));
        verify(chatMemory, never()).add(anyString(), any(List.class));
    }

    @Test
    void prePersistedUserDoesNotSkipNewToolResponse() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        UserMessage currentUser = UserMessage.builder().text("current")
                .metadata(Map.of(ChatMemory.CONVERSATION_ID, "user:ctx:universal_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, true))
                .build();
        AssistantMessage call = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}"))).build();
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "lookup", "result"))).build();

        advisor.before(ChatClientRequest.builder().prompt(new Prompt(List.of(currentUser, call, response))).build(),
                mock(AdvisorChain.class));

        verify(chatMemory).add("user:ctx:universal_assistant", response);
        verify(chatMemory, never()).get(anyString());
    }

    @Test
    void historicalPrePersistedFlagDoesNotSkipCurrentUserPersistence() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        UserMessage previousUser = UserMessage.builder().text("previous")
                .metadata(Map.of(ChatMemory.CONVERSATION_ID, "user:ctx:universal_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, true))
                .build();
        UserMessage currentUser = UserMessage.builder().text("current")
                .metadata(Map.of(ChatMemory.CONVERSATION_ID, "user:ctx:universal_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_GRAPH_HISTORY_PREPARED, true))
                .build();

        advisor.before(ChatClientRequest.builder().prompt(new Prompt(List.of(previousUser,
                new AssistantMessage("previous answer"), currentUser))).build(), mock(AdvisorChain.class));

        verify(chatMemory).add("user:ctx:universal_assistant", currentUser);
        verify(chatMemory, never()).get(anyString());
    }

    @Test
    void prePersistedUserMessageSkipsMemoryAddButStillReadsHistory() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get(anyString())).thenReturn(List.of());
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);

        UserMessage userMessage = UserMessage.builder()
                .text("query")
                .metadata(Map.of(
                        ChatMemory.CONVERSATION_ID, "user:ctx:universal_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, Boolean.TRUE))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        advisor.before(request, chain);

        verify(chatMemory).get("user:ctx:universal_assistant");
        verify(chatMemory, never()).add(anyString(), any(Message.class));
        verify(chatMemory, never()).add(anyString(), any(List.class));
    }

    @Test
    void prePersistedUserMessagePreservesSystemMessageFromInstructions() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("user:ctx:universal_assistant")).thenReturn(List.of(
                new UserMessage("hello")));
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);

        SystemMessage systemMessage = new SystemMessage("你是 J2Agent AI 通用助手");
        UserMessage userMessage = UserMessage.builder()
                .text("hello")
                .metadata(Map.of(
                        ChatMemory.CONVERSATION_ID, "user:ctx:universal_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, Boolean.TRUE))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(systemMessage, userMessage)))
                .build();

        ChatClientRequest processed = advisor.before(request, chain);
        List<Message> messages = processed.prompt().getInstructions();

        assertEquals(2, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertTrue(messages.get(0).getText().contains("J2Agent"));
        verify(chatMemory, never()).add(anyString(), any(Message.class));
    }

    @Test
    void prePersistedUserMessageDoesNotDuplicateUserInPrompt() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("user:ctx:universal_assistant")).thenReturn(List.of(
                new UserMessage("上一轮"),
                new AssistantMessage("上一轮回答"),
                new UserMessage("hello")));
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);

        UserMessage userMessage = UserMessage.builder()
                .text("hello")
                .metadata(Map.of(
                        ChatMemory.CONVERSATION_ID, "user:ctx:universal_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, Boolean.TRUE))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        ChatClientRequest processed = advisor.before(request, chain);
        List<Message> messages = processed.prompt().getInstructions();

        assertEquals(3, messages.size());
        long userCount = messages.stream().filter(UserMessage.class::isInstance).count();
        assertEquals(2, userCount);
        assertEquals("hello", ((UserMessage) messages.get(2)).getText());
        verify(chatMemory, never()).add(anyString(), any(Message.class));
    }

    @Test
    void prePersistedUserMessageReplacesTrailingUserWithLiveMedia() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("user:ctx:universal_assistant")).thenReturn(List.of(new UserMessage("")));
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);
        Media media = Media.builder()
                .mimeType(MediaType.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1}))
                .build();

        UserMessage userMessage = UserMessage.builder()
                .text("")
                .media(media)
                .metadata(Map.of(
                        ChatMemory.CONVERSATION_ID, "user:ctx:universal_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, Boolean.TRUE))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        ChatClientRequest processed = advisor.before(request, chain);
        UserMessage lastUser = processed.prompt().getUserMessage();

        assertEquals(1, processed.prompt().getInstructions().size());
        assertFalse(lastUser.getMedia().isEmpty());
        verify(chatMemory, never()).add(anyString(), any(Message.class));
    }

    @Test
    void subAgentFlagOnlyInResponseContextDoesNotSkipAssistantPersistence() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AssistantMessage answer = new AssistantMessage("answer");
        ChatClientResponse response = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(answer))))
                .context(Map.of(ChatMemory.CONVERSATION_ID, "user:ctx:rc_wiki_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_SUB_AGENT_CALL_RUN, true)).build();

        advisor.after(response, mock(AdvisorChain.class));

        verify(chatMemory).add("user:ctx:rc_wiki_assistant", List.of(answer));
    }

    @Test
    void delegatedStreamPersistsResponseUsingConversationContext() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("user:ctx:rc_wiki_assistant")).thenReturn(List.of());
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        UserMessage user = UserMessage.builder().text("query")
                .metadata(Map.of(ChatMemory.CONVERSATION_ID, "user:ctx:rc_wiki_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_SUB_AGENT_CALL_RUN, true)).build();
        AssistantMessage answer = new AssistantMessage("answer");
        when(chain.nextStream(any(ChatClientRequest.class))).thenReturn(Flux.just(ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(answer)))).build()));

        List<ChatClientResponse> responses = advisor.adviseStream(ChatClientRequest.builder()
                .prompt(new Prompt(List.of(user))).build(), chain).collectList().block(Duration.ofSeconds(5));

        assertEquals("user:ctx:rc_wiki_assistant", responses.getFirst().context().get(ChatMemory.CONVERSATION_ID));
        verify(chatMemory).get("user:ctx:rc_wiki_assistant");
        verify(chatMemory).add("user:ctx:rc_wiki_assistant", user);
        verify(chatMemory).add("user:ctx:rc_wiki_assistant", List.of(answer));
    }


}
