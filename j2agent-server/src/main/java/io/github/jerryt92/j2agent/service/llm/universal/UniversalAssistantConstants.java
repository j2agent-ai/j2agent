package io.github.jerryt92.j2agent.service.llm.universal;

/**
 * 平台通用助手（通用 AI 助手 / 知识库问答助手）常量。
 */
public final class UniversalAssistantConstants {

    public static final String AGENT_ID = "universal_assistant";

    public static final String DISPLAY_NAME = "通用AI助手";

    public static final String KNOWLEDGE_QA_AGENT_ID = "knowledge_qa_assistant";

    public static final String KNOWLEDGE_QA_DISPLAY_NAME = "通用知识库问答助手";

    private UniversalAssistantConstants() {
    }

    public static boolean isUniversalAssistant(String agentId) {
        return AGENT_ID.equals(agentId);
    }

    public static boolean isKnowledgeQaAssistant(String agentId) {
        return KNOWLEDGE_QA_AGENT_ID.equals(agentId);
    }
}
