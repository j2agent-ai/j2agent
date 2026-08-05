package io.github.jerryt92.j2agent.service.llm.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppendOnlyWindowChatMemoryTest {

    @Test
    void getReplacesOrphanToolCallWithFailureAssistant() {
        ChatMemoryRepository repository = mock(ChatMemoryRepository.class);
        String conversationId = "user:ctx:agent";
        AssistantMessage orphanToolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "demo", "{}")))
                .build();
        when(repository.findByConversationId(conversationId)).thenReturn(List.of(
                new UserMessage("hello"),
                orphanToolCall,
                new UserMessage("why")));
        AppendOnlyWindowChatMemory memory = new AppendOnlyWindowChatMemory(repository, 10);

        List<Message> replay = memory.get(conversationId);

        assertEquals(3, replay.size());
        assertEquals("hello", replay.get(0).getText());
        AssistantMessage repaired = assertInstanceOf(AssistantMessage.class, replay.get(1));
        assertEquals("发生错误", repaired.getText());
        assertEquals("why", replay.get(2).getText());
    }

    @Test
    void getKeepsValidToolCallAndToolResponsePair() {
        ChatMemoryRepository repository = mock(ChatMemoryRepository.class);
        String conversationId = "user:ctx:agent";
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "demo", "{}")))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "demo", "ok")))
                .build();
        when(repository.findByConversationId(conversationId)).thenReturn(List.of(toolCall, toolResponse));
        AppendOnlyWindowChatMemory memory = new AppendOnlyWindowChatMemory(repository, 10);

        List<Message> replay = memory.get(conversationId);

        assertEquals(2, replay.size());
        assertInstanceOf(AssistantMessage.class, replay.get(0));
        assertInstanceOf(ToolResponseMessage.class, replay.get(1));
    }
}
