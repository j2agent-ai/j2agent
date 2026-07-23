package io.github.jerryt92.j2agent.service.llm.agent.inf;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.config.plugin.PluginProperties;
import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.model.I18nString;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.service.rag.RagSourceFileService;
import io.github.jerryt92.j2agent.service.rag.query.DefaultQueryTransformers;
import io.github.jerryt92.j2agent.service.llm.advisor.EmptyQuerySkippingRetrievalAugmentationAdvisor;
import io.github.jerryt92.j2agent.service.llm.advisor.ReactCompatibleMessageChatMemoryAdvisor;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunContext;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.agent.inf.feature.McpFeature;
import io.github.jerryt92.j2agent.service.llm.mcp.McpService;
import io.github.jerryt92.j2agent.service.llm.skill.AgentClassLoaderSkillRegistry;
import io.github.jerryt92.j2agent.service.llm.skill.AgentSkillsAgentHook;
import io.github.jerryt92.j2agent.service.llm.skill.AgentUiSkillLoadToolInterceptor;
import io.github.jerryt92.j2agent.service.llm.tool.AgentToolErrorReturnInterceptor;
import io.github.jerryt92.j2agent.service.llm.tool.AgentUiToolEventInterceptor;
import io.github.jerryt92.j2agent.tools.AskQuestionTool;
import io.github.jerryt92.j2agent.utils.I18nLocaleUtils;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI Agent 统一入口，屏蔽不同 Agent 的构建和执行细节。
 */
public abstract class AiAgent {
    /**
     * Agent 内部 {@code resources/skills/} 在 classpath 上的路径段。
     */
    private static final String INTERNAL_SKILLS_CLASSPATH = "skills";
    private static final String DEFAULT_QA_TEMPLATE_RESOURCE = "qa-template.json";

    public abstract String getAgentId();

    public abstract I18nString getAgentName();

    public abstract I18nString getAgentDescription();

    /** 按 locale 解析智能体展示名称。 */
    public final String resolveAgentName(String locale) {
        return resolveI18nString(getAgentName(), locale);
    }

    /** 按 locale 解析智能体描述。 */
    public final String resolveAgentDescription(String locale) {
        return resolveI18nString(getAgentDescription(), locale);
    }

    private String resolveI18nString(I18nString value, String locale) {
        if (value == null) {
            return "";
        }
        String resolved = CommonConstants.EN_US.equals(locale) ? value.getEnUS() : value.getZhCN();
        if (StringUtils.hasText(resolved)) {
            return resolved.trim();
        }
        String fallback = CommonConstants.EN_US.equals(locale) ? value.getZhCN() : value.getEnUS();
        return StringUtils.hasText(fallback) ? fallback.trim() : "";
    }

    /**
     * 编排提示词：仅供通用助手开放召回等内部编排使用，不展示到界面。
     * 未 override 或返回空时，{@link #resolveOrchestrationPrompt()} 回退为中文 Agent 描述。
     */
    public String getOrchestrationPrompt() {
        return null;
    }

    /** 路由侧实际使用的编排文案。 */
    public final String resolveOrchestrationPrompt() {
        String orchestration = getOrchestrationPrompt();
        return StringUtils.hasText(orchestration) ? orchestration.trim() : resolveAgentDescription(CommonConstants.ZH_CN);
    }

    public abstract String loadSystemPrompt();

    public int getSort() {
        return 100;
    }

    public String getLogo() {
        return "🤖";
    }

    /**
     * Agent 级深度思考默认策略；单轮可被 {@link io.github.jerryt92.j2agent.model.ChatRequestDto#getThinkingMode()} 覆盖。
     *
     * <p>子类示例：{@code return AgentThinkingOverride.OFF;}
     */
    public AgentThinkingOverride getThinkingOverride() {
        return AgentThinkingOverride.USE_PROVIDER_DEFAULT;
    }

    /**
     * 是否展示热门问题模板；默认不展示，子类显式开启。<br/>
     * 开启后需在 resource 中添加 qa-template.json。<br/>
     * qa-template.json 示例：
     * <pre>{@code
     * {
     *   "zh_CN": [
     *     "AI助手可以提供哪些帮助和技术支持"
     *   ],
     *   "en_US": [
     *     "What can AI Assistant provide help and technical support"
     *   ]
     * }
     * }</pre>
     */
    public boolean isQaTemplateEnabled() {
        return false;
    }

    /**
     * 是否启用平台级 ask_question 工具；默认所有 Agent 开启，特殊 Agent 可覆盖为 false。
     */
    protected boolean enableAskQuestion() {
        return true;
    }

    /**
     * 热门问题模板资源路径（相对 Agent 定义类 ClassLoader）。
     * 插件 Agent 默认 JAR 根 {@code qa-template.json}；内置 Agent 可 override 为独立路径。
     */
    protected String getQaTemplateResourcePath() {
        return DEFAULT_QA_TEMPLATE_RESOURCE;
    }

    /**
     * 从 Agent 定义类 ClassLoader 读取问答模板，按 locale 返回问题文案列表。
     */
    protected List<String> loadQaTemplateQuestions(String locale) {
        Class<?> owner = AopUtils.getTargetClass(this);
        String resourcePath = getQaTemplateResourcePath();
        try (InputStream inputStream = owner.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                return Collections.emptyList();
            }
            JSONObject qaTemplateJson = JSONObject.parse(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
            String localeKey = CommonConstants.ZH_CN.equals(locale) ? CommonConstants.ZH_CN : CommonConstants.EN_US;
            if (qaTemplateJson.getJSONArray(localeKey) == null) {
                return Collections.emptyList();
            }
            return qaTemplateJson.getJSONArray(localeKey).toList(String.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 随机抽取指定数量的热门问题；未开启模板或无资源时返回空列表。
     */
    public List<String> pickQaTemplateQuestions(String locale, int limit) {
        if (!isQaTemplateEnabled()) {
            return Collections.emptyList();
        }
        List<String> qaTemplates = new ArrayList<>(loadQaTemplateQuestions(locale));
        int total = qaTemplates.size();
        if (total == 0) {
            return Collections.emptyList();
        }
        if (limit > total) {
            limit = total;
        }
        Collections.shuffle(qaTemplates);
        return qaTemplates.subList(0, limit);
    }

    /**
     * 在 {@link #initAgent()} / {@link #rebuildAgent()} 中装配；volatile 保证 MCP 刷新替换实例后对读线程可见。
     */
    protected volatile Agent agent;

    private static final ThreadLocal<UserContextBo> CURRENT_USER_CONTEXT = new ThreadLocal<>();

    @Autowired
    protected ChatModel chatModel;

    /**
     * 如需自定义记忆策略，请覆盖此变量。
     */
    @Getter
    @Autowired
    @Qualifier("defaultChatMemory")
    protected ChatMemory chatMemory;

    /**
     * Agent 工具事件 UI 事件发射器
     */
    @Autowired
    protected AgentUiToolEventInterceptor agentUiToolEventInterceptor;

    /**
     * Agent 技能加载事件 UI 事件发射器
     */
    @Autowired
    protected AgentUiSkillLoadToolInterceptor agentUiSkillLoadToolInterceptor;

    /**
     * 工具链最外层兜底：畸形 tool_call 分片与执行异常转为 ToolResponse 回注 LLM。
     */
    @Autowired
    protected AgentToolErrorReturnInterceptor agentToolErrorReturnInterceptor;

    @Autowired
    protected RagSourceFileService ragSourceFileService;

    @Autowired
    protected PluginProperties pluginProperties;

    @Autowired
    protected McpService mcpService;

    @Autowired
    private ObjectProvider<PluginProperties> pluginPropertiesProvider;

    @Autowired
    private Environment environment;

    @Autowired
    private org.springframework.beans.factory.ObjectProvider<
            io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentService> chatAttachmentServiceProvider;

    @Autowired
    private ObjectProvider<AskQuestionTool> askQuestionToolProvider;

    @Autowired(required = false)
    private DefaultQueryTransformers defaultQueryTransformers;

    /**
     * Spring 完成字段注入后再构建底层 {@link Agent}，保证 {@link #chatModel}、{@link #chatMemory} 等已就绪。
     */
    @PostConstruct
    protected void initAgent() {
        rebuildAgent();
    }

    /**
     * MCP 等外部依赖变更后重建底层 Agent，与首次 {@link #initAgent()} 使用同一套 {@link #buildAgent()} 逻辑。
     */
    public void rebuildAgent() {
        this.agent = buildAgent().build();
    }

    /** 当前流式调用绑定的用户上下文。 */
    protected final UserContextBo currentUserContext() {
        return CURRENT_USER_CONTEXT.get();
    }

    /** 当前流式调用语言（归一化为 zh_CN / en_US）。 */
    protected final String currentLanguage() {
        UserContextBo userContext = currentUserContext();
        return userContext == null ? CommonConstants.ZH_CN : I18nLocaleUtils.normalizeLanguage(userContext.getLanguage());
    }

    /** 当前流式调用是否为英文。 */
    protected final boolean currentLanguageIsEnglish() {
        return CommonConstants.EN_US.equals(currentLanguage());
    }

    /**
     * 以流式方式执行单轮 Agent 调用；会话历史由 ChatClient 上的记忆 Advisor 维护，
     * {@link ChatMemory#CONVERSATION_ID} 放在首条 {@link UserMessage} 的 metadata 中以便跨线程解析。
     */
    public Flux<NodeOutput> stream(AgentRunContext context) throws GraphRunnerException {
        if (agent == null) {
            throw new IllegalStateException("Agent not initialized: " + getAgentId());
        }
        RunnableConfig.Builder configBuilder = RunnableConfig.builder()
                .threadId(context.conversationId())
                .addMetadata(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, context.turnId());
        RunnableConfig runnableConfig = configBuilder.build();
        if (context.toolEventEmitter() != null) {
            runnableConfig.context().put(
                    AgentUiToolEventInterceptor.CONTEXT_KEY_TOOL_EVENT_EMITTER, context.toolEventEmitter());
        }
        runnableConfig.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, context.conversationId());
        runnableConfig.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CONTEXT_ID, context.contextId());
        runnableConfig.context().put(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, context.turnId());
        runnableConfig.context().put(AgentRunnableContextKeys.CONTEXT_KEY_USER_ID, context.userId());
        runnableConfig.context().put(AgentRunnableContextKeys.CONTEXT_KEY_AGENT_ID, context.agentId());
        if (context.userContext() != null) {
            runnableConfig.context().put(AgentRunnableContextKeys.CONTEXT_KEY_USER_CONTEXT, context.userContext());
        }
        if (StringUtils.hasText(context.language())) {
            runnableConfig.context().put(AgentRunnableContextKeys.CONTEXT_KEY_LANGUAGE, context.language());
        }
        if (!context.knowledgeCollections().isEmpty()) {
            runnableConfig.context().put(
                    AgentRunnableContextKeys.CONTEXT_KEY_KNOWLEDGE_COLLECTIONS,
                    context.knowledgeCollections());
        }
        if (context.subAgentCallRun()) {
            runnableConfig.context().put(AgentRunnableContextKeys.CONTEXT_KEY_SUB_AGENT_CALL_RUN, Boolean.TRUE);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChatMemory.CONVERSATION_ID, context.conversationId());
        metadata.put("attachments", context.attachments());
        if (!context.knowledgeCollections().isEmpty()) {
            metadata.put(
                    AgentRunnableContextKeys.CONTEXT_KEY_KNOWLEDGE_COLLECTIONS,
                    context.knowledgeCollections());
        }
        if (context.subAgentCallRun()) {
            metadata.put(ReactCompatibleMessageChatMemoryAdvisor.META_SUB_AGENT_CALL_RUN, Boolean.TRUE);
        }
        if (context.userMessagePrePersisted()) {
            metadata.put(ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, Boolean.TRUE);
        }
        var userMessageBuilder = UserMessage.builder()
                .text(context.text())
                .metadata(metadata);
        if (!context.attachments().isEmpty()) {
            var attachmentService = chatAttachmentServiceProvider.getIfAvailable();
            if (attachmentService == null) {
                throw new IllegalStateException("Object storage is required for image input.");
            }
            userMessageBuilder.media(attachmentService.toMedia(context.attachments()));
        }
        UserMessage userMessage = userMessageBuilder.build();
        return Flux.defer(() -> {
            UserContextBo previousUserContext = CURRENT_USER_CONTEXT.get();
            bindCurrentUserContext(context.userContext());
            ReactCompatibleMessageChatMemoryAdvisor.setConversationId(context.conversationId());
            try {
                List<Message> messages = new ArrayList<>();
                String runtimeSystemPrompt = resolveRuntimeSystemPrompt();
                if (StringUtils.hasText(runtimeSystemPrompt)) {
                    messages.add(new SystemMessage(runtimeSystemPrompt));
                }
                messages.add(userMessage);
                return agent.stream(messages, runnableConfig)
                        .doFinally(signalType -> {
                            restoreCurrentUserContext(previousUserContext);
                            ReactCompatibleMessageChatMemoryAdvisor.clear();
                        });
            } catch (GraphRunnerException ex) {
                restoreCurrentUserContext(previousUserContext);
                ReactCompatibleMessageChatMemoryAdvisor.clear();
                return Flux.error(ex);
            }
        });
    }

    private void bindCurrentUserContext(UserContextBo userContext) {
        if (userContext == null) {
            CURRENT_USER_CONTEXT.remove();
            return;
        }
        CURRENT_USER_CONTEXT.set(userContext);
    }

    private void restoreCurrentUserContext(UserContextBo previousUserContext) {
        if (previousUserContext == null) {
            CURRENT_USER_CONTEXT.remove();
            return;
        }
        CURRENT_USER_CONTEXT.set(previousUserContext);
    }

    private String resolveRuntimeSystemPrompt() {
        String systemPrompt = loadSystemPrompt();
        if (!currentLanguageIsEnglish()) {
            return systemPrompt;
        }
        return appendEnglishOutputInstruction(systemPrompt);
    }

    private String appendEnglishOutputInstruction(String systemPrompt) {
        if (!StringUtils.hasText(systemPrompt)) {
            return CommonConstants.ENGLISH_LLM_PROMPT;
        }
        return systemPrompt.trim() + "\n\n" + CommonConstants.ENGLISH_LLM_PROMPT;
    }

    /**
     * 构建保序文档合并器，避免默认按分值重排导致上下文顺序变化。
     * 如需自定义，请覆盖此方法。
     *
     * @return 保序文档合并器
     */
    protected DocumentJoiner buildOrderPreservingDocumentJoiner() {
        return documentsForQuery -> {
            Map<String, Document> documentsById = new LinkedHashMap<>();
            documentsForQuery.values().forEach(documentGroups ->
                    documentGroups.forEach(documents ->
                            documents.forEach(document -> documentsById.putIfAbsent(document.getId(), document))));
            return new ArrayList<>(documentsById.values());
        };
    }

    /**
     * RAG 查询增强策略：无命中时保留原问题；有命中时在原问题后追加知识库片段，不阻断工具与技能调用。
     * 子类可按 Agent 场景覆盖。
     * <p>若使用 {@link ContextualQueryAugmenter} 且 {@code allowEmptyContext(false)}，
     * {@code emptyContextPromptTemplate} 不得含 {@code {query}} 等占位符——框架在无文档时调用
     * {@code render()} 不传变量。需在空命中消息中保留用户问题时，应设 {@code allowEmptyContext(true)}
     * 或实现自定义 {@link QueryAugmenter}。</p>
     */
    protected QueryAugmenter buildQueryAugmenter() {
        PromptTemplate promptTemplate = new PromptTemplate("""
                用户问题：{query}
                
                以下为知识库检索参考（可能不完整，可结合工具与技能继续推理）：
                ---------------------
                {context}
                ---------------------
                """);
        return ContextualQueryAugmenter.builder()
                .promptTemplate(promptTemplate)
                .allowEmptyContext(true)
                .build();
    }

    /**
     * 如需赋予 Agent RAG 能力，请覆盖此方法。
     *
     * @return
     */
    protected DocumentRetriever buildDocumentRetriever() {
        return null;
    }

    /**
     * 平台生命周期服务用于发现 Agent 随附的 RAG retriever。
     */
    public final DocumentRetriever resolveDocumentRetriever() {
        return buildDocumentRetriever();
    }

    /**
     * 是否向前端展示 RAG 命中的知识库文件来源（实时 WebSocket PATCH + 历史回放 srcFile）。
     * 默认关闭；{@code rag_infos} 仍正常落库，仅 UI 层按本开关过滤。
     */
    public boolean isRagSourceDisplayEnabled() {
        return false;
    }

    /**
     * RAG 检索前 Query 预处理链（Multimodal → Compression → Rewrite）。
     * 子类返回空数组可关闭；默认走 {@link DefaultQueryTransformers}。
     */
    protected QueryTransformer[] buildQueryTransformers() {
        if (defaultQueryTransformers == null) {
            return new QueryTransformer[0];
        }
        return defaultQueryTransformers.build(
                chatAttachmentServiceProvider.getIfAvailable());
    }

    /**
     * 如需赋予 Agent 工具能力，请覆盖此方法。
     *
     * @return
     */
    protected Object[] buildTools() {
        return new Object[0];
    }

    /**
     * 如需在 ReactAgent 上挂载 Hook（如意图注入、技能披露），请覆盖此方法。
     *
     * @return Hook 实例数组；默认无 Hook。内置技能目录存在时，基类另通过 {@link #buildSkillsAgentHook()} 自动挂载。
     */
    protected Hook[] buildHooks() {
        return new Hook[0];
    }

    /**
     * 将 {@link #buildTools()} 转为 {@link ToolCallback}；子类可覆盖以自定义本地工具合并。
     * MCP 由 {@link #buildEffectiveToolCallbacks()} 在 {@link #buildAgent()} 中统一追加，不受本方法覆盖影响。
     *
     * @return 工具回调数组
     */
    protected ToolCallback[] buildToolCallbacks() {
        Object[] tools = buildTools();
        if (tools == null || tools.length == 0) {
            return new ToolCallback[0];
        }
        List<ToolCallback> toolCallbackList = new ArrayList<>(Arrays.asList(ToolCallbacks.from(tools)));
        return toolCallbackList.stream().filter(Objects::nonNull).toArray(ToolCallback[]::new);
    }

    /**
     * 最终注入 ReactAgent 的工具链：子类 {@link #buildToolCallbacks()} 结果 + {@link McpFeature} MCP 工具。
     * 即使子类覆盖 {@code buildToolCallbacks()}，只要 {@link #buildAgent()} 走基类逻辑（含 {@code super.buildAgent()}），
     * 已实现 {@link McpFeature} 的 Agent 仍会合并 MCP。
     */
    private ToolCallback[] buildEffectiveToolCallbacks() {
        List<ToolCallback> effectiveToolCallbacks = new ArrayList<>(Arrays.asList(buildToolCallbacks()));
        appendAskQuestionToolCallbackIfNeeded(effectiveToolCallbacks);
        appendMcpToolCallbacksIfNeeded(effectiveToolCallbacks);
        return effectiveToolCallbacks.stream().filter(Objects::nonNull).toArray(ToolCallback[]::new);
    }

    private void appendAskQuestionToolCallbackIfNeeded(List<ToolCallback> toolCallbackList) {
        if (!enableAskQuestion() || askQuestionToolProvider == null) {
            return;
        }
        AskQuestionTool askQuestionTool = askQuestionToolProvider.getIfAvailable();
        if (askQuestionTool == null) {
            return;
        }
        toolCallbackList.add(askQuestionTool);
    }

    private void appendMcpToolCallbacksIfNeeded(List<ToolCallback> toolCallbackList) {
        if (!(this instanceof McpFeature mcpFeature) || mcpService == null) {
            return;
        }
        ToolCallback[] mcpCallbacks = mcpService.getToolCallbacksForAgent(mcpFeature);
        if (mcpCallbacks == null) {
            return;
        }
        Collections.addAll(toolCallbackList, mcpCallbacks);
    }

    /**
     * {@link ReactAgent.Builder#tools} 不允许数组中含 null，对子类合并结果做兜底过滤。
     */
    private static ToolCallback[] withoutNullToolCallbacks(ToolCallback[] callbacks) {
        if (callbacks == null || callbacks.length == 0) {
            return new ToolCallback[0];
        }
        return Arrays.stream(callbacks).filter(Objects::nonNull).toArray(ToolCallback[]::new);
    }

    private AgentSkillsAgentHook buildSkillsAgentHook() {
        PluginProperties effectivePluginProperties = resolvePluginProperties();
        AgentClassLoaderSkillRegistry agentSkillRegistry = AgentClassLoaderSkillRegistry.builder()
                .agent(this)
                .pluginProperties(effectivePluginProperties)
                .pluginPathOverride(resolvePluginPath())
                .internalSkillsClasspath(INTERNAL_SKILLS_CLASSPATH)
                .build();
        List<SkillMetadata> loadedSkillMetadata = agentSkillRegistry.listAll();
        if (loadedSkillMetadata == null || loadedSkillMetadata.isEmpty()) {
            return null;
        }
        Map<String, List<ToolCallback>> groupedTools = new LinkedHashMap<>();
        loadedSkillMetadata.forEach(metadata -> groupedTools.put(metadata.getName(), List.of()));
        return AgentSkillsAgentHook.builder()
                .skillRegistry(agentSkillRegistry)
                .groupedTools(groupedTools)
                .build();
    }

    private PluginProperties resolvePluginProperties() {
        if (pluginProperties != null) {
            return pluginProperties;
        }
        return pluginPropertiesProvider.getIfAvailable();
    }

    private String resolvePluginPath() {
        PluginProperties effective = resolvePluginProperties();
        if (effective != null && StringUtils.hasText(effective.resolvePath())) {
            return effective.resolvePath();
        }
        return environment != null ? environment.getProperty("j2agent.plugin.path") : null;
    }

    private Hook[] buildEffectiveHooks() {
        List<Hook> hooks = new ArrayList<>();
        AgentSkillsAgentHook skillsHook = buildSkillsAgentHook();
        if (skillsHook != null) {
            hooks.add(skillsHook);
        }
        Hook[] customHooks = buildHooks();
        if (customHooks != null) {
            Arrays.stream(customHooks).filter(Objects::nonNull).forEach(hooks::add);
        }
        return hooks.toArray(new Hook[0]);
    }

    /**
     * React 拦截器链；最外层为工具异常兜底，其内为 UI 事件与技能加载。
     *
     * @return 拦截器实例数组
     */
    protected Interceptor[] buildInterceptors() {
        return new Interceptor[]{
                agentToolErrorReturnInterceptor,
                agentUiToolEventInterceptor,
                agentUiSkillLoadToolInterceptor
        };
    }

    /**
     * 最终生效的拦截器链。即使子类覆盖 {@link #buildInterceptors()}，也保留工具异常兜底，
     * 避免模型偶发误调用未知工具名时直接中断整轮流式响应。
     */
    private Interceptor[] buildEffectiveInterceptors() {
        Interceptor[] customInterceptors = buildInterceptors();
        List<Interceptor> effectiveInterceptors = new ArrayList<>();
        if (agentToolErrorReturnInterceptor != null) {
            effectiveInterceptors.add(agentToolErrorReturnInterceptor);
        }
        if (customInterceptors != null) {
            Arrays.stream(customInterceptors)
                    .filter(Objects::nonNull)
                    .filter(interceptor -> interceptor != agentToolErrorReturnInterceptor)
                    .forEach(effectiveInterceptors::add);
        }
        return effectiveInterceptors.toArray(new Interceptor[0]);
    }

    /**
     * 如需使用其他策略的 Agent，请覆盖此方法。
     *
     * @return
     */
    protected Builder buildAgent() {
        ReactCompatibleMessageChatMemoryAdvisor memoryAdvisor = ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        DocumentRetriever documentRetriever = buildDocumentRetriever();
        ChatClient chatClient;
        if (documentRetriever == null) {
            chatClient = ChatClient.builder(chatModel)
                    .defaultAdvisors(memoryAdvisor)
                    .build();
        } else {
            var ragAdvisorBuilder = RetrievalAugmentationAdvisor.builder()
                    .documentRetriever(documentRetriever)
                    .documentJoiner(buildOrderPreservingDocumentJoiner())
                    .queryAugmenter(buildQueryAugmenter());
            QueryTransformer[] queryTransformers = buildQueryTransformers();
            if (queryTransformers.length > 0) {
                ragAdvisorBuilder.queryTransformers(queryTransformers);
            }
            RetrievalAugmentationAdvisor ragAdvisor = ragAdvisorBuilder.build();
            chatClient = ChatClient.builder(chatModel)
                    .defaultAdvisors(memoryAdvisor,
                            EmptyQuerySkippingRetrievalAugmentationAdvisor.wrap(ragAdvisor))
                    .build();
        }
        Builder builder = ReactAgent.builder()
                .name(getAgentId())
                .chatClient(chatClient)
                .tools(withoutNullToolCallbacks(buildEffectiveToolCallbacks()))
                .systemPrompt("")
                .interceptors(buildEffectiveInterceptors());
        Hook[] effectiveHooks = buildEffectiveHooks();
        if (effectiveHooks.length > 0) {
            builder = builder.hooks(effectiveHooks);
        }
        return builder;
    }
}
