package io.github.jerryt92.j2agent.service.llm.skill;

import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogContext;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogSnapshot;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import io.github.jerryt92.j2agent.service.llm.tool.AgentUiToolEventInterceptor;
import io.github.jerryt92.j2agent.service.llm.tool.ToolEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 拦截官方 {@code read_skill} 工具：通过 {@link ToolEventEmitter#onSkillLoadStart} 等驱动 {@link io.github.jerryt92.j2agent.model.AgentState#LOAD_SKILL}，
 * 并在成功后写入审计消息到会话记忆表。
 */
@Slf4j
@Component
public class AgentUiSkillLoadToolInterceptor extends ToolInterceptor {

    private final ChatMemory chatMemory;
    private final ChatMemoryMessageCodec chatMemoryMessageCodec;

    /**
     * 注入默认会话记忆与消息编解码器。
     */
    public AgentUiSkillLoadToolInterceptor(
            @Qualifier("defaultChatMemory") ChatMemory chatMemory,
            ChatMemoryMessageCodec chatMemoryMessageCodec) {
        this.chatMemory = chatMemory;
        this.chatMemoryMessageCodec = chatMemoryMessageCodec;
    }

    @Override
    public String getName() {
        return "AgentUiSkillLoad";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String toolName = request.getToolName();
        if (!ReadSkillTool.READ_SKILL.equals(toolName)) {
            return handler.call(request);
        }
        Optional<ToolEventEmitter> emitterOpt = resolveEmitter(request);
        Optional<String> conversationIdOpt = resolveConversationId(request);
        String args = request.getArguments() == null ? "" : request.getArguments();
        ReadSkillCall skillCall = parseReadSkillCall(args);
        String skillName = skillCall.skillName();
        String callId = request.getToolCallId();
        emitterOpt.ifPresent(e -> e.onSkillLoadStart(callId, toolName, args, skillName));
        long startNanos = System.nanoTime();
        String conversationId = conversationIdOpt.orElse(null);
        try {
            ToolCallResponse response = handler.call(request);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            String rawResult = response.getResult();
            int fullLen = rawResult == null ? 0 : rawResult.length();
            boolean truncated = fullLen > ToolEventEmitter.MAX_TOOL_RESULT_LENGTH;
            if (response.isError()) {
                Throwable err = toFailureThrowable(response);
                logSkillLoad(request, conversationId, skillCall, "FAILED", durationMs, err.getMessage());
                emitterOpt.ifPresent(e -> e.onSkillLoadFailure(callId, toolName, skillName, err, durationMs));
                persistAudit(conversationIdOpt, skillName, false, fullLen, truncated, err.getMessage());
                return response;
            }
            logSkillLoad(request, conversationId, skillCall, "SUCCESS", durationMs, null);
            emitterOpt.ifPresent(e -> e.onSkillLoadSuccess(callId, toolName, skillName, rawResult, durationMs));
            persistAudit(conversationIdOpt, skillName, true, fullLen, truncated, null);
            return response;
        } catch (Throwable t) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logSkillLoad(request, conversationId, skillCall, "FAILED", durationMs, t.getMessage());
            emitterOpt.ifPresent(e -> e.onSkillLoadFailure(callId, toolName, skillName, t, durationMs));
            persistAudit(conversationIdOpt, skillName, false, 0, false, t.getMessage());
            throw t;
        }
    }

    /**
     * 将技能加载结果以审计形态写入 {@link ChatMemory}（底层 chat_context_item）。
     * 直接聊天与委派调用均写入各自会话，审计消息由 codec 排除在模型回放之外。
     */
    private void persistAudit(Optional<String> conversationIdOpt,
                              String skillName,
                              boolean success,
                              int contentLength,
                              boolean truncated,
                              String errorMessage) {
        if (conversationIdOpt.isEmpty()) {
            log.warn("RunnableConfig 缺少 chatConversationId，跳过技能加载审计落库");
            return;
        }
        try {
            AssistantMessage audit = chatMemoryMessageCodec.buildSkillLoadAuditMessage(
                    skillName, success, contentLength, truncated, errorMessage);
            chatMemory.add(conversationIdOpt.get(), audit);
        } catch (Exception e) {
            log.warn("技能加载审计落库失败: {}", e.getMessage(), e);
        }
    }

    private static ReadSkillCall parseReadSkillCall(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new ReadSkillCall("", "");
        }
        try {
            JSONObject o = JSONObject.parseObject(argumentsJson);
            String n = o.getString("skillName");
            if (n == null || n.isBlank()) {
                n = o.getString("skill_name");
            }
            String p = o.getString("relativePath");
            if (p == null || p.isBlank()) {
                p = o.getString("relative_path");
            }
            return new ReadSkillCall(n != null ? n.trim() : "", p != null ? p.trim() : "");
        } catch (Exception e) {
            return new ReadSkillCall("", "");
        }
    }

    private static Optional<ToolEventEmitter> resolveEmitter(ToolCallRequest request) {
        return request.getExecutionContext()
                .map(ctx -> ctx.config().context().get(AgentUiToolEventInterceptor.CONTEXT_KEY_TOOL_EVENT_EMITTER))
                .filter(ToolEventEmitter.class::isInstance)
                .map(ToolEventEmitter.class::cast);
    }

    private static Optional<String> resolveConversationId(ToolCallRequest request) {
        return request.getExecutionContext()
                .map(ctx -> ctx.config().context().get(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID))
                .filter(String.class::isInstance)
                .map(String.class::cast);
    }

    private static void logSkillLoad(ToolCallRequest request,
                                     String conversationId,
                                     ReadSkillCall skillCall,
                                     String status,
                                     long durationMs,
                                     String errorMessage) {
        AgentRunLogSnapshot snapshot = resolveSnapshot(request, conversationId);
        if (snapshot == null) {
            return;
        }
        AgentRunLogger.info(snapshot, AgentRunEventType.SKILL_LOAD,
                AgentRunLogger.kv(
                        "skill", skillCall.skillName(),
                        "relativePath", skillCall.relativePath(),
                        "status", status,
                        "durationMs", durationMs),
                errorMessage == null ? "skill loaded" : errorMessage);
    }

    private static AgentRunLogSnapshot resolveSnapshot(ToolCallRequest request, String conversationId) {
        AgentRunLogSnapshot snapshot = AgentRunLogContext.lookup(conversationId);
        if (snapshot != null) {
            return snapshot;
        }
        return request.getExecutionContext()
                .map(ctx -> ctx.config().context())
                .map(context -> new AgentRunLogSnapshot(
                        stringValue(context.get(AgentRunnableContextKeys.CONTEXT_KEY_CONTEXT_ID)),
                        stringValue(context.get(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID)),
                        stringValue(context.get(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID)),
                        stringValue(context.get(AgentRunnableContextKeys.CONTEXT_KEY_USER_ID)),
                        stringValue(context.get(AgentRunnableContextKeys.CONTEXT_KEY_AGENT_ID))))
                .filter(s -> s.conversationId() != null && !s.conversationId().isBlank())
                .orElse(null);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Throwable toFailureThrowable(ToolCallResponse response) {
        Object msg = response.getMetadata().get("errorMessage");
        if (msg != null) {
            return new RuntimeException(String.valueOf(msg));
        }
        String result = response.getResult();
        return new RuntimeException(result != null ? result : "tool error");
    }

    /** read_skill 调用参数解析结果。 */
    private record ReadSkillCall(String skillName, String relativePath) {
    }
}
