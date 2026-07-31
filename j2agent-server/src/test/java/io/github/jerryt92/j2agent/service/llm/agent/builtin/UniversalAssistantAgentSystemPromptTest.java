package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import io.github.jerryt92.j2agent.service.llm.agent.builtin.universalagent.UniversalAssistantAgent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniversalAssistantAgentSystemPromptTest {

    @Test
    void loadSystemPromptContainsJ2AgentIdentity() {
        UniversalAssistantAgent agent = new UniversalAssistantAgent();
        String prompt = agent.loadSystemPrompt();
        assertTrue(prompt.contains("J2Agent"));
        assertTrue(prompt.contains("AI 通用助手"));
        assertTrue(prompt.contains("编排服务"));
        assertTrue(prompt.contains("子智能体"));
        assertTrue(prompt.contains("必须调用 ask_question 工具"));
        assertTrue(prompt.contains("禁止用普通文本反问"));
        assertFalse(prompt.isBlank());
    }
}
