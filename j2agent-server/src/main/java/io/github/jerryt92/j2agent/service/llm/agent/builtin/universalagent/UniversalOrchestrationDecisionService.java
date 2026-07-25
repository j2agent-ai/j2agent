package io.github.jerryt92.j2agent.service.llm.agent.builtin.universalagent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.service.llm.LlmSyncService;
import io.github.jerryt92.j2agent.service.llm.agent.builtin.OrchestrationTraceEntry;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import io.github.jerryt92.j2agent.service.llm.chat.TurnCancelledException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 编排决策 LLM：在候选与已执行 Trace 基础上输出 invoke 或 complete。
 */
@Slf4j
@Service
public class UniversalOrchestrationDecisionService {

    private static final String DECISION_SYSTEM_PROMPT = """
            你是通用助手的子智能体编排决策器。根据候选列表、对话上下文与已执行的子智能体调用记录，输出唯一 JSON 对象（不要 Markdown）：
            {"action":"invoke"|"complete","agentId":"...","reason":"..."}
            规则：
            1. action=invoke 时 agentId 必填；子智能体将直接接收完整父会话上下文，无需提炼 query
            2. action=complete 时表示无需再调用子智能体；agentId 可省略
            3. 已调用过的 agentId 不得再次 invoke（见已执行列表）
            4. 候选均不适用或问题已由子智能体充分回答时选 complete
            5. 使用与用户相同的语言撰写 reason""";

    private final LlmSyncService llmSyncService;

    public UniversalOrchestrationDecisionService(LlmSyncService llmSyncService) {
        this.llmSyncService = llmSyncService;
    }

    public OrchestrationDecision decide(
            String candidatesJson,
            String routingQuery,
            List<OrchestrationTraceEntry> trace,
            Set<String> invokedAgentIds,
            boolean forceComplete) {
        return decide(candidatesJson, routingQuery, trace, invokedAgentIds, forceComplete, null);
    }

    public OrchestrationDecision decide(
            String candidatesJson,
            String routingQuery,
            List<OrchestrationTraceEntry> trace,
            Set<String> invokedAgentIds,
            boolean forceComplete,
            String turnId) {
        return decide(candidatesJson, routingQuery, trace, invokedAgentIds, forceComplete, turnId, null);
    }

    public OrchestrationDecision decide(
            String candidatesJson,
            String routingQuery,
            List<OrchestrationTraceEntry> trace,
            Set<String> invokedAgentIds,
            boolean forceComplete,
            String turnId,
            String conversationId) {
        if (ChatTurnCancellationRegistry.isCancelled(turnId)) {
            throw new TurnCancelledException(turnId);
        }
        if (forceComplete) {
            return OrchestrationDecision.complete("max rounds reached");
        }
        if (UniversalIntentQueryService.isCandidatesEmpty(candidatesJson)) {
            return OrchestrationDecision.complete("no candidates");
        }
        String traceBlock = formatTrace(trace);
        String invokedBlock = invokedAgentIds == null || invokedAgentIds.isEmpty()
                ? "（无）"
                : invokedAgentIds.stream().collect(Collectors.joining(", "));
        String userBlock = """
                【候选子智能体 JSON】
                %s

                【对话与路由上下文】
                %s

                【本回合已执行子调用】
                %s

                【已调用 agentId（不可重复）】
                %s
                """.formatted(
                candidatesJson.trim(),
                routingQuery.trim(),
                traceBlock,
                invokedBlock);
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(DECISION_SYSTEM_PROMPT),
                    new UserMessage(userBlock)));
            String raw = llmSyncService.callAssistantText(prompt, conversationId);
            if (ChatTurnCancellationRegistry.isCancelled(turnId)) {
                throw new TurnCancelledException(turnId);
            }
            return parseDecision(raw);
        } catch (TurnCancelledException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("orchestration decision LLM failed: {}", ex.toString());
            return OrchestrationDecision.complete("decision LLM error");
        }
    }

    static OrchestrationDecision parseDecision(String raw) {
        if (StringUtils.isBlank(raw)) {
            return OrchestrationDecision.complete("empty decision");
        }
        String json = extractJsonObject(raw.trim());
        if (json == null) {
            return OrchestrationDecision.complete("invalid decision json");
        }
        try {
            JSONObject obj = JSON.parseObject(json);
            if (obj == null) {
                return OrchestrationDecision.complete("null decision");
            }
            String action = StringUtils.defaultIfBlank(obj.getString("action"), "complete").trim().toLowerCase();
            if ("invoke".equals(action)) {
                String agentId = StringUtils.trimToNull(obj.getString("agentId"));
                if (agentId == null) {
                    return OrchestrationDecision.complete("incomplete invoke");
                }
                String query = StringUtils.trimToNull(obj.getString("query"));
                return OrchestrationDecision.invoke(agentId, query, StringUtils.defaultString(obj.getString("reason")));
            }
            return OrchestrationDecision.complete(StringUtils.defaultString(obj.getString("reason")));
        } catch (Exception ex) {
            log.warn("orchestration decision parse failed: {}", ex.toString());
            return OrchestrationDecision.complete("parse error");
        }
    }

    private static String formatTrace(List<OrchestrationTraceEntry> trace) {
        if (trace == null || trace.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (OrchestrationTraceEntry entry : trace) {
            sb.append(entry.toTraceBlock()).append('\n');
        }
        return sb.toString().trim();
    }

    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    public record OrchestrationDecision(String action, String agentId, String query, String reason) {

        public static OrchestrationDecision invoke(String agentId, String query, String reason) {
            return new OrchestrationDecision("invoke", agentId, query, reason);
        }

        public static OrchestrationDecision complete(String reason) {
            return new OrchestrationDecision("complete", null, null, reason);
        }

        public boolean isInvoke() {
            return "invoke".equals(action);
        }

        public boolean isComplete() {
            return !isInvoke();
        }
    }
}
