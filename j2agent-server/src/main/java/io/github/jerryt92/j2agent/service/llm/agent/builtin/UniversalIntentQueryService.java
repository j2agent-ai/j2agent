package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.service.llm.LlmSyncService;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import io.github.jerryt92.j2agent.service.llm.chat.TurnCancelledException;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import io.github.jerryt92.j2agent.service.rag.query.QueryUserMessageSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通用助手意图召回：由 {@link UniversalAssistantOrchestratorService} 在 ChatService 前置调用，
 * 结合图内 messages 与编排召回 LLM 返回子智能体候选 JSON。
 */
@Slf4j
@Service
public class UniversalIntentQueryService {

    private static final String INTENT_QUERY_SYSTEM_PROMPT = """
            你是 AI 助手的开放召回器，采用开放召回策略：尽可能列出可能相关的专业智能体，供下游通用助手自行决策。
            只输出一个 JSON 数组，不要 Markdown、不要解释。每项字段：
            - agentId: 候选列表中的 agentId（必填）
            - name: 智能体名称（必填）
            - relevance: high | medium | low（必填）
            - reason: 简短说明为何可能相关（必填）
            规则：
            1. 召回优先、宁多勿漏：只要 orchestrationPrompt 与用户问题存在合理可能关联，即应列入；职责重叠时允许多项并列，勿因只能选一个而删减
            2. relevance 从宽：high=高度吻合典型问法；medium=可能相关、交由下游判断；low=弱相关但仍值得一试（如多个 Wiki 类 Agent 不确定时）
            3. 按相关度降序，最多返回 5 项；仅当与全部 orchestrationPrompt 完全无关（如纯寒暄）时返回 []
            4. agentId 必须来自候选列表；依据 orchestrationPrompt（能力域与典型问法）判断，勿仅依据 name
            5. 流程/文档/操作类问题：术语不完全匹配时（如「小程序」「发布流程」），凡涉及内部文档、发布测试、产品说明的，至少返回 1～2 个 Wiki 类候选
            6. 你不做最终裁决：多个候选可同时返回，用 reason 说明各自理由
            7. 使用与用户相同的语言撰写 reason""";

    private static final int ROUTING_LOG_MAX_CHARS = 2000;

    /** 路由上下文最多保留的对话轮数（user + assistant 各计一条）。 */
    private static final int MAX_ROUTING_CONTEXT_ROUNDS = 3;

    /** 历史纯图用户消息在 routing 上下文中的占位行。 */
    static final String IMAGE_ONLY_DIALOGUE_LINE = "用户: [图片]";

    private final AgentRouter agentRouter;
    private final LlmSyncService llmSyncService;

    public UniversalIntentQueryService(AgentRouter agentRouter, LlmSyncService llmSyncService) {
        this.agentRouter = agentRouter;
        this.llmSyncService = llmSyncService;
    }

    /**
     * 结合会话记忆、图内 messages 与可选编排 Trace 构造路由查询文本（开放召回、编排决策与子智能体入参共用）。
     */
    public String buildRoutingQuery(ChatMemory memory,
                                    String conversationId,
                                    List<Message> messages,
                                    String orchestrationTrace) {
        String latestUser = extractLatestUserText(messages);
        boolean hasAttachments = hasAttachmentsInMessages(messages);
        List<String> contextLines = resolveContextLines(memory, conversationId, messages, latestUser);
        if (StringUtils.isBlank(latestUser) && hasAttachments) {
            contextLines = dedupeTrailingImageMarker(contextLines);
        }
        if (StringUtils.isBlank(latestUser)
                && contextLines.isEmpty()
                && StringUtils.isBlank(orchestrationTrace)
                && !hasAttachments) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!contextLines.isEmpty()) {
            sb.append("【对话上下文】\n");
            for (String line : contextLines) {
                sb.append(line).append('\n');
            }
            sb.append('\n');
        }
        if (StringUtils.isNotBlank(orchestrationTrace)) {
            sb.append(orchestrationTrace.trim()).append("\n\n");
        }
        if (StringUtils.isNotBlank(latestUser)) {
            sb.append("【本轮问题】\n").append(latestUser.trim());
        } else if (hasAttachments) {
            sb.append("【本轮问题】\n（无文字，含图片附件）");
        }
        return sb.toString().trim();
    }

    /**
     * 从图内 messages 或会话记忆中解析本轮用户附件（优先图内最后一条 user）。
     */
    public static List<ChatAttachmentDto> resolveLatestAttachments(List<Message> messages,
                                                                   ChatMemory memory,
                                                                   String conversationId) {
        UserMessage fromMessages = findLastUserMessage(messages);
        if (fromMessages != null) {
            List<ChatAttachmentDto> fromMessage = ChatMemoryMessageCodec.attachmentsFromUserMessage(fromMessages);
            if (!fromMessage.isEmpty()) {
                return List.copyOf(fromMessage);
            }
        }
        if (memory == null || StringUtils.isBlank(conversationId)) {
            return List.of();
        }
        List<Message> stored = memory.get(conversationId);
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        for (int i = stored.size() - 1; i >= 0; i--) {
            Message message = stored.get(i);
            if (message instanceof UserMessage userMessage) {
                List<ChatAttachmentDto> attachments = ChatMemoryMessageCodec.attachmentsFromUserMessage(userMessage);
                if (!attachments.isEmpty()) {
                    return List.copyOf(attachments);
                }
                return List.of();
            }
        }
        return List.of();
    }

    private static UserMessage findLastUserMessage(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof UserMessage userMessage) {
                return userMessage;
            }
        }
        return null;
    }

    /**
     * 从图内 messages 与可选编排 Trace 构造路由查询文本（无 ChatMemory 时回退）。
     */
    public String buildRoutingQueryFromMessages(List<Message> messages, String orchestrationTrace) {
        return buildRoutingQuery(null, null, messages, orchestrationTrace);
    }

    static String extractLatestUserText(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof UserMessage userMessage) {
                String text = userMessage.getText();
                if (StringUtils.isNotBlank(text)) {
                    return text.trim();
                }
            }
        }
        return "";
    }

    static boolean isCandidatesEmpty(String candidatesJson) {
        if (StringUtils.isBlank(candidatesJson)) {
            return true;
        }
        String trimmed = candidatesJson.trim();
        return "[]".equals(trimmed);
    }

    /**
     * 结合会话记忆与本轮用户消息，构造面向编排召回 LLM 的查询文本。
     */
    public String buildRoutingQuery(ChatMemory memory, String conversationId, String latestUserMessage) {
        if (StringUtils.isBlank(latestUserMessage)) {
            return "";
        }
        return buildRoutingQuery(memory, conversationId, List.of(new UserMessage(latestUserMessage.trim())), null);
    }

    private static List<String> resolveContextLines(ChatMemory memory,
                                                    String conversationId,
                                                    List<Message> messages,
                                                    String latestUser) {
        List<String> contextLines;
        if (memory != null && StringUtils.isNotBlank(conversationId)) {
            List<Message> stored = memory.get(conversationId);
            if (stored != null && !stored.isEmpty()) {
                contextLines = extractRecentDialogueLines(stored, MAX_ROUTING_CONTEXT_ROUNDS);
            } else {
                contextLines = extractRecentDialogueLines(messages, MAX_ROUTING_CONTEXT_ROUNDS);
            }
        } else {
            contextLines = extractRecentDialogueLines(messages, MAX_ROUTING_CONTEXT_ROUNDS);
        }
        return dedupeTrailingUserLine(contextLines, latestUser);
    }

    static List<String> dedupeTrailingUserLine(List<String> contextLines, String latestUser) {
        if (contextLines == null || contextLines.isEmpty() || StringUtils.isBlank(latestUser)) {
            return contextLines == null ? List.of() : contextLines;
        }
        List<String> mutable = new ArrayList<>(contextLines);
        String expected = "用户: " + latestUser.trim();
        if (!mutable.isEmpty() && expected.equals(mutable.get(mutable.size() - 1))) {
            mutable.remove(mutable.size() - 1);
        }
        return List.copyOf(mutable);
    }

    static List<String> dedupeTrailingImageMarker(List<String> contextLines) {
        if (contextLines == null || contextLines.isEmpty()) {
            return contextLines == null ? List.of() : contextLines;
        }
        List<String> mutable = new ArrayList<>(contextLines);
        if (!mutable.isEmpty() && IMAGE_ONLY_DIALOGUE_LINE.equals(mutable.get(mutable.size() - 1))) {
            mutable.remove(mutable.size() - 1);
        }
        return List.copyOf(mutable);
    }

    static boolean hasAttachmentsInMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof UserMessage userMessage) {
                return !ChatMemoryMessageCodec.attachmentsFromUserMessage(userMessage).isEmpty();
            }
        }
        return false;
    }

    /**
     * 对路由查询执行开放召回，返回清洗后的候选 JSON 数组字符串。
     */
    public String queryIntentAgents(String routingQuery) {
        return queryIntentAgents(null, routingQuery, null);
    }

    /**
     * 对路由查询执行开放召回，返回清洗后的候选 JSON 数组字符串。
     *
     * @param conversationId 父回合会话键，用于写入 {@code agent-run.log}；可为 null（单测等场景）
     */
    public String queryIntentAgents(String conversationId, String routingQuery) {
        return queryIntentAgents(conversationId, routingQuery, null);
    }

    /**
     * 对路由查询执行开放召回，返回清洗后的候选 JSON 数组字符串。
     *
     * @param conversationId 父回合会话键，用于写入 {@code agent-run.log}；可为 null（单测等场景）
     * @param turnId         回合 ID，用于中断检查；可为 null
     */
    public String queryIntentAgents(String conversationId, String routingQuery, String turnId) {
        if (ChatTurnCancellationRegistry.isCancelled(turnId)) {
            throw new TurnCancelledException(turnId);
        }
        if (StringUtils.isBlank(routingQuery)) {
            return "[]";
        }
        List<AiAgent> candidates = agentRouter.listCallableSubAgents();
        if (candidates.isEmpty()) {
            return "[]";
        }
        String candidateBlock = formatCandidateBlock(candidates);
        if (log.isDebugEnabled()) {
            log.debug("intent recall candidate block:\n{}", candidateBlock);
        }
        String userBlock = """
                【候选专业智能体】
                %s

                【用户问题】
                %s
                """.formatted(candidateBlock, routingQuery.trim());
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(INTENT_QUERY_SYSTEM_PROMPT),
                    new UserMessage(userBlock)));
            String raw = llmSyncService.callAssistantText(prompt, conversationId);
            if (ChatTurnCancellationRegistry.isCancelled(turnId)) {
                throw new TurnCancelledException(turnId);
            }
            String sanitized = sanitizeCandidateJson(raw, candidates, conversationId);
            logIntentRecall(conversationId, "raw", truncateForLog(raw));
            logIntentRecall(conversationId, "sanitized", truncateForLog(sanitized));
            return sanitized;
        } catch (TurnCancelledException ex) {
            throw ex;
        } catch (Exception ex) {
            AgentRunLogger.warnByConversationId(
                    conversationId,
                    AgentRunEventType.INTENT_RECALL,
                    AgentRunLogger.kv("phase", "llmError", "errorType", ex.getClass().getSimpleName()),
                    "intent recall LLM failed: " + ex);
            return "[]";
        }
    }

    private static void logIntentRecall(String conversationId, String phase, String payload) {
        if (!StringUtils.isNotBlank(conversationId)) {
            return;
        }
        AgentRunLogger.infoByConversationId(
                conversationId,
                AgentRunEventType.INTENT_RECALL,
                AgentRunLogger.kv("phase", phase),
                "intent recall " + phase + " (truncated): " + payload);
    }

    static List<String> extractRecentDialogueLines(List<Message> messages, int maxRounds) {
        if (messages == null || messages.isEmpty() || maxRounds <= 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Message message : messages) {
            String line = toDialogueLine(message);
            if (line != null) {
                lines.add(line);
            }
        }
        int maxLines = maxRounds * 2;
        if (lines.size() <= maxLines) {
            return List.copyOf(lines);
        }
        return List.copyOf(lines.subList(lines.size() - maxLines, lines.size()));
    }

    private static String toDialogueLine(Message message) {
        if (message instanceof UserMessage userMessage) {
            String text = userMessage.getText();
            if (StringUtils.isNotBlank(text)
                    && !QueryUserMessageSupport.isImageOnlyQueryPlaceholder(text.trim())) {
                return "用户: " + text.trim();
            }
            if (!ChatMemoryMessageCodec.attachmentsFromUserMessage(userMessage).isEmpty()) {
                return IMAGE_ONLY_DIALOGUE_LINE;
            }
            return null;
        }
        if (message instanceof AssistantMessage assistantMessage) {
            if (assistantMessage.hasToolCalls()) {
                return null;
            }
            String text = assistantMessage.getText();
            return StringUtils.isNotBlank(text) ? "助手: " + text.trim() : null;
        }
        return null;
    }

    static String formatCandidateBlock(List<AiAgent> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            AiAgent agent = candidates.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("【").append(agent.getAgentId()).append("】\n");
            sb.append("name: ").append(agent.getAgentName()).append('\n');
            sb.append("orchestrationPrompt: ").append(normalizeOrchestrationPromptText(agent.resolveOrchestrationPrompt()));
        }
        return sb.toString();
    }

    static String normalizeOrchestrationPromptText(String orchestrationPrompt) {
        if (StringUtils.isBlank(orchestrationPrompt)) {
            return "";
        }
        return orchestrationPrompt.trim().replaceAll("\\s+", " ");
    }

    static String sanitizeCandidateJson(String raw, List<AiAgent> candidates) {
        return sanitizeCandidateJson(raw, candidates, null);
    }

    static String sanitizeCandidateJson(String raw, List<AiAgent> candidates, String conversationId) {
        if (StringUtils.isBlank(raw)) {
            return "[]";
        }
        String json = extractJsonArray(raw.trim());
        if (json == null) {
            return "[]";
        }
        try {
            JSONArray arr = JSON.parseArray(json);
            if (arr == null || arr.isEmpty()) {
                return "[]";
            }
            Set<String> knownIds = candidates.stream()
                    .map(AiAgent::getAgentId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, AiAgent> agentById = candidates.stream()
                    .collect(Collectors.toMap(AiAgent::getAgentId, a -> a, (a, b) -> a, LinkedHashMap::new));
            JSONArray filtered = new JSONArray();
            for (int i = 0; i < arr.size() && filtered.size() < 5; i++) {
                JSONObject item = arr.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String agentId = item.getString("agentId");
                if (StringUtils.isBlank(agentId) || !knownIds.contains(agentId.trim())) {
                    continue;
                }
                String trimmedAgentId = agentId.trim();
                AiAgent matched = agentById.get(trimmedAgentId);
                JSONObject normalized = new JSONObject();
                normalized.put("agentId", trimmedAgentId);
                normalized.put("name", StringUtils.defaultIfBlank(item.getString("name"), trimmedAgentId));
                if (matched != null) {
                    normalized.put("orchestrationPrompt", normalizeOrchestrationPromptText(matched.resolveOrchestrationPrompt()));
                }
                String relevance = item.getString("relevance");
                normalized.put("relevance", normalizeRelevance(relevance));
                normalized.put("reason", StringUtils.defaultIfBlank(item.getString("reason"), ""));
                filtered.add(normalized);
            }
            return filtered.toJSONString();
        } catch (Exception ex) {
            AgentRunLogger.warnByConversationId(
                    conversationId,
                    AgentRunEventType.INTENT_RECALL,
                    AgentRunLogger.kv("phase", "parseError", "errorType", ex.getClass().getSimpleName()),
                    "intent recall JSON parse failed: " + ex);
            return "[]";
        }
    }

    private static String normalizeRelevance(String relevance) {
        if (StringUtils.isBlank(relevance)) {
            return "medium";
        }
        String normalized = relevance.trim().toLowerCase();
        return switch (normalized) {
            case "high", "medium", "low" -> normalized;
            default -> "medium";
        };
    }

    private static String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static String truncateForLog(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= ROUTING_LOG_MAX_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, ROUTING_LOG_MAX_CHARS) + "...(truncated)";
    }
}
