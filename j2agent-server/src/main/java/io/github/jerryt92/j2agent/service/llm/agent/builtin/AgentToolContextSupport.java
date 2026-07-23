package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.llm.tool.AgentUiToolEventInterceptor;
import io.github.jerryt92.j2agent.service.llm.tool.ToolEventEmitter;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

/**
 * 从 Spring AI {@link ToolContext} 解析 {@link com.alibaba.cloud.ai.graph.RunnableConfig#context()} 注入的回合键。
 */
public final class AgentToolContextSupport {

    private AgentToolContextSupport() {
    }

    public static Map<String, Object> contextMap(ToolContext toolContext) {
        Map<String, Object> holder = SubAgentCallTurnContextHolder.context();
        if (!holder.isEmpty()) {
            return holder;
        }
        if (toolContext == null || toolContext.getContext() == null) {
            return Map.of();
        }
        return toolContext.getContext();
    }

    public static String stringKey(ToolContext toolContext, String key) {
        Object raw = contextMap(toolContext).get(key);
        if (raw == null) {
            return null;
        }
        String value = raw.toString();
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    public static String contextId(ToolContext toolContext) {
        return stringKey(toolContext, AgentRunnableContextKeys.CONTEXT_KEY_CONTEXT_ID);
    }

    public static String turnId(ToolContext toolContext) {
        return stringKey(toolContext, AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID);
    }

    public static String userId(ToolContext toolContext) {
        return stringKey(toolContext, AgentRunnableContextKeys.CONTEXT_KEY_USER_ID);
    }

    public static UserContextBo userContext(ToolContext toolContext) {
        Object raw = contextMap(toolContext).get(AgentRunnableContextKeys.CONTEXT_KEY_USER_CONTEXT);
        return raw instanceof UserContextBo userContext ? userContext : null;
    }

    public static String language(ToolContext toolContext) {
        UserContextBo userContext = userContext(toolContext);
        if (userContext != null && StringUtils.isNotBlank(userContext.getLanguage())) {
            return userContext.getLanguage().trim();
        }
        return stringKey(toolContext, AgentRunnableContextKeys.CONTEXT_KEY_LANGUAGE);
    }

    public static String parentConversationId(ToolContext toolContext) {
        return stringKey(toolContext, AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID);
    }

    public static ToolEventEmitter toolEventEmitter(ToolContext toolContext) {
        Object raw = contextMap(toolContext).get(AgentUiToolEventInterceptor.CONTEXT_KEY_TOOL_EVENT_EMITTER);
        return raw instanceof ToolEventEmitter emitter ? emitter : null;
    }
}
