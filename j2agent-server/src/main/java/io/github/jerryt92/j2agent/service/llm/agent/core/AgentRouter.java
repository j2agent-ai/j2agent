package io.github.jerryt92.j2agent.service.llm.agent.core;

import io.github.jerryt92.j2agent.model.AgentInfoDto;
import io.github.jerryt92.j2agent.model.AgentInfoList;
import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.universal.UniversalAssistantConstants;
import io.github.jerryt92.j2agent.utils.I18nLocaleUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 路由器，根据 WebSocket 传入的 agentId 选择具体 Agent。
 * <p>首次 {@link #refresh()} 由 {@link AgentPluginRegistry}（{@code ApplicationReadyEvent}）
 * 及 {@link AgentPluginReloadService} 触发，不在构造/初始化阶段刷新，避免与通用助手工具循环依赖。
 */
@Slf4j
@Component
public class AgentRouter {

    private final ApplicationContext applicationContext;
    private volatile Map<String, AiAgent> agents = Map.of();

    public AgentRouter(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 从 Spring 容器重建 agentId → Agent 映射；存在重复 agentId 时抛出异常。
     */
    public synchronized void refresh() {
        Map<String, AiAgent> next = new LinkedHashMap<>();
        String[] beanNames = applicationContext.getBeanNamesForType(AiAgent.class, true, false);
        for (String beanName : beanNames) {
            AiAgent agent;
            String agentId;
            try {
                agent = applicationContext.getBean(beanName, AiAgent.class);
                agentId = agent.getAgentId();
            } catch (Exception ex) {
                log.error("Skip failed AiAgent bean while refreshing router: beanName={}", beanName, ex);
                continue;
            }
            if (next.containsKey(agentId)) {
                throw new IllegalStateException("Duplicate agentId in container: " + agentId);
            }
            next.put(agentId, agent);
        }
        this.agents = Map.copyOf(next);
    }

    /**
     * 解析 agentId
     */
    public AiAgent route(String agentId) {
        Map<String, AiAgent> snapshot = agents;
        AiAgent agent = snapshot.get(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("Unsupported agentId: " + agentId);
        }
        return agent;
    }

    /**
     * 列出所有已注册智能体（按 sort、agentId 排序），供前端展示卡片。
     */
    public AgentInfoList listRegisteredAgents(String language) {
        String locale = I18nLocaleUtils.normalizeLanguage(language);
        List<AgentInfoDto> items = agents.values().stream()
                .filter(a -> !UniversalAssistantConstants.isUniversalAssistant(a.getAgentId()))
                .filter(a -> !UniversalAssistantConstants.isKnowledgeQaAssistant(a.getAgentId()))
                .sorted(Comparator.comparingInt(AiAgent::getSort)
                        .thenComparing(AiAgent::getAgentId))
                .map(a -> new AgentInfoDto()
                        .agentId(a.getAgentId())
                        .name(a.resolveAgentName(locale))
                        .description(a.resolveAgentDescription(locale))
                        .showHotQuestions(a.isQaTemplateEnabled())
                        .sort(a.getSort())
                        .logo(a.getLogo()))
                .collect(Collectors.toList());
        return new AgentInfoList().agents(items);
    }

    /**
     * 可被通用助手调用的全部子智能体。
     */
    public List<AiAgent> listCallableSubAgents() {
        return agents.values().stream()
                .filter(a -> !UniversalAssistantConstants.isUniversalAssistant(a.getAgentId()))
                .filter(a -> !UniversalAssistantConstants.isKnowledgeQaAssistant(a.getAgentId()))
                .sorted(Comparator.comparingInt(AiAgent::getSort)
                        .thenComparing(AiAgent::getAgentId))
                .collect(Collectors.toList());
    }

    /**
     * 解析智能体展示名；未知 id 时回退为 id 本身。
     */
    public String resolveAgentName(String agentId) {
        if (agentId == null) {
            return "";
        }
        AiAgent agent = agents.get(agentId);
        if (agent == null && "assistant".equals(agentId)) {
            agent = agents.get("chat_assistant");
        }
        if (agent == null) {
            return agentId;
        }
        String name = agent.resolveAgentName(CommonConstants.ZH_CN);
        return !name.isBlank() ? name.trim() : agentId;
    }
}
