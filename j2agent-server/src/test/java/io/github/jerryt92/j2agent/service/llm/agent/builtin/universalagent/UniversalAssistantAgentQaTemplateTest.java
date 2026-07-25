package io.github.jerryt92.j2agent.service.llm.agent.builtin.universalagent;

import io.github.jerryt92.j2agent.constants.CommonConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniversalAssistantAgentQaTemplateTest {

    private UniversalAssistantAgent universalAssistantAgent;

    @BeforeEach
    void setUp() {
        universalAssistantAgent = new UniversalAssistantAgent();
    }

    @Test
    void loadsDedicatedQaTemplateResource() {
        assertEquals(
                "prompts/universal-assistant-qa-template.json",
                universalAssistantAgent.getQaTemplateResourcePath());

        List<String> questions = universalAssistantAgent.pickQaTemplateQuestions(CommonConstants.ZH_CN, 10);

        assertEquals(5, questions.size());
        assertTrue(questions.stream().anyMatch(q -> q.contains("J2Agent")));
        assertTrue(questions.stream().anyMatch(q -> q.contains("RAG")));
    }

    @Test
    void serverClasspathHasNoRootQaTemplateJson() {
        assertNull(
                UniversalAssistantAgent.class.getClassLoader().getResource("qa-template.json"),
                "server 根目录 qa-template.json 不应存在，避免插件子智能体串读");
    }

    @Test
    void qaTemplateDisabledAgentsReturnEmpty() {
        UniversalAssistantAgent disabled = new UniversalAssistantAgent() {
            @Override
            public boolean isQaTemplateEnabled() {
                return false;
            }
        };
        assertTrue(disabled.pickQaTemplateQuestions(CommonConstants.ZH_CN, 5).isEmpty());
    }
}
