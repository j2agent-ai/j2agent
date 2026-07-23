package io.github.jerryt92.j2agent.service.llm.agent.core;

/**
 * 放入 {@link com.alibaba.cloud.ai.graph.RunnableConfig#context()} 的键名常量，供工具拦截器等读取。
 */
public final class AgentRunnableContextKeys {

    private AgentRunnableContextKeys() {
    }

    /**
     * 复合会话 ID（与 {@link org.springframework.ai.chat.memory.ChatMemory} 使用的 conversationId 一致），用于工具链内落库。
     */
    public static final String CONTEXT_KEY_CHAT_CONVERSATION_ID =
            AgentRunnableContextKeys.class.getName() + ".chatConversationId";

    public static final String CONTEXT_KEY_CONTEXT_ID =
            AgentRunnableContextKeys.class.getName() + ".contextId";

    public static final String CONTEXT_KEY_TURN_ID =
            AgentRunnableContextKeys.class.getName() + ".turnId";

    public static final String CONTEXT_KEY_USER_ID =
            AgentRunnableContextKeys.class.getName() + ".userId";

    public static final String CONTEXT_KEY_USER_CONTEXT =
            AgentRunnableContextKeys.class.getName() + ".userContext";

    public static final String CONTEXT_KEY_AGENT_ID =
            AgentRunnableContextKeys.class.getName() + ".agentId";

    public static final String CONTEXT_KEY_LANGUAGE =
            AgentRunnableContextKeys.class.getName() + ".language";

    public static final String CONTEXT_KEY_KNOWLEDGE_COLLECTIONS =
            AgentRunnableContextKeys.class.getName() + ".knowledgeCollections";

    /**
     * 子智能体调用标记：为 true 时记忆 Advisor 不读写子智能体记忆表（无状态子调用，兼容旧 Orchestrator）。
     */
    public static final String CONTEXT_KEY_SUB_AGENT_CALL_RUN =
            AgentRunnableContextKeys.class.getName() + ".subAgentCallRun";

    /**
     * @deprecated 仅用于读取历史 RunnableConfig；新代码请使用 {@link #CONTEXT_KEY_SUB_AGENT_CALL_RUN}。
     */
    @Deprecated
    public static final String CONTEXT_KEY_DELEGATE_RUN =
            AgentRunnableContextKeys.class.getName() + ".delegateRun";
}
