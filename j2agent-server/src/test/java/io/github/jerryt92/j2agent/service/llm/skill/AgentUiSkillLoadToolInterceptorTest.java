package io.github.jerryt92.j2agent.service.llm.skill;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 技能加载拦截器单测：直接聊天与委派子智能体调用均持久化技能审计。
 */
class AgentUiSkillLoadToolInterceptorTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void skillAuditPersistsForDirectAndDelegatedCalls(boolean subAgentCallRun) throws JsonProcessingException {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ChatMemoryMessageCodec codec = mock(ChatMemoryMessageCodec.class);
        AssistantMessage audit = new AssistantMessage("audit");
        when(codec.buildSkillLoadAuditMessage(anyString(), any(Boolean.class), any(Integer.class),
                any(Boolean.class), any())).thenReturn(audit);

        AgentUiSkillLoadToolInterceptor interceptor = new AgentUiSkillLoadToolInterceptor(chatMemory, codec);
        ToolCallHandler handler = mock(ToolCallHandler.class);
        RunnableConfig config = RunnableConfig.builder().build();
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_SUB_AGENT_CALL_RUN, subAgentCallRun);
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, "user:ctx:rc_wiki_assistant");
        ToolCallRequest request = ToolCallRequest.builder().toolName(ReadSkillTool.READ_SKILL)
                .toolCallId("call-1").arguments("{\"skillName\":\"mysql8-sql\"}")
                .executionContext(new ToolCallExecutionContext(config, new OverAllState())).build();
        when(handler.call(request)).thenReturn(ToolCallResponse.of("call-1", ReadSkillTool.READ_SKILL, "ok"));

        interceptor.interceptToolCall(request, handler);

        verify(chatMemory).add("user:ctx:rc_wiki_assistant", audit);
    }
}
