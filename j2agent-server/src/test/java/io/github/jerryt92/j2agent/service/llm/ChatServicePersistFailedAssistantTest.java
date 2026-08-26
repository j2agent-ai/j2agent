package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.config.chat.ActiveChatTurnProperties;
import io.github.jerryt92.j2agent.config.chat.ChatInputProperties;
import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamSession;
import io.github.jerryt92.j2agent.service.llm.agent.builtin.universalagent.UniversalAssistantOrchestratorService;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnControlService;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import io.github.jerryt92.j2agent.service.llm.queue.ChatOutputEventCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServicePersistFailedAssistantTest {

    @Test
    void persistFailedAssistantWritesFallbackWhenNoStreamedText() {
        ChatService service = newChatService();
        ChatMemory chatMemory = mock(ChatMemory.class);
        String conversationId = "user:ctx:agent";

        service.persistFailedAssistant(chatMemory, conversationId, new StringBuilder(), new StringBuilder(),
                new Object(), new AtomicBoolean(false));

        ArgumentCaptor<List<Message>> captor = messageListCaptor();
        verify(chatMemory).add(eq(conversationId), captor.capture());
        Message saved = captor.getValue().get(0);
        assertInstanceOf(AssistantMessage.class, saved);
        assertEquals("[Error]", saved.getText());
    }

    @Test
    void persistFailedAssistantClosesTrailingToolCallBeforeFallbackMessage() {
        ChatService service = newChatService();
        ChatMemory chatMemory = mock(ChatMemory.class);
        String conversationId = "user:ctx:agent";
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "demo", "{}")))
                .build();
        when(chatMemory.get(conversationId)).thenReturn(List.of(toolCall));

        service.persistFailedAssistant(chatMemory, conversationId, new StringBuilder(), new StringBuilder(),
                new Object(), new AtomicBoolean(false));

        verify(chatMemory).get(conversationId);
        ArgumentCaptor<List<Message>> addCaptor = messageListCaptor();
        verify(chatMemory, times(2)).add(eq(conversationId), addCaptor.capture());

        ToolResponseMessage toolResponse = assertInstanceOf(ToolResponseMessage.class,
                addCaptor.getAllValues().get(0).get(0));
        assertEquals("call-1", toolResponse.getResponses().get(0).id());
        assertEquals("demo", toolResponse.getResponses().get(0).name());
        assertEquals("[Error]", toolResponse.getResponses().get(0).responseData());
        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, addCaptor.getAllValues().get(1).get(0));
        assertEquals("[Error]", assistant.getText());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Message>> messageListCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static ChatService newChatService() {
        return new ChatService(
                mock(ChatContextService.class),
                mock(AgentRouter.class),
                mock(ChatInputProperties.class),
                mock(ActiveChatTurnProperties.class),
                mock(ChatMemoryMessageCodec.class),
                mock(ActiveChatTurnRegistry.class),
                mock(ObjectProvider.class),
                mock(AgentStreamSession.class),
                mock(UniversalAssistantOrchestratorService.class),
                mock(ChatOutputEventCache.class),
                mock(ChatTurnControlService.class));
    }
}
