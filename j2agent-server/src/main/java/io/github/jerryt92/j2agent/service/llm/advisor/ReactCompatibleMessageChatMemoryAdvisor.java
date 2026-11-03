package io.github.jerryt92.j2agent.service.llm.advisor;

import io.github.jerryt92.j2agent.service.llm.StreamedAssistantPersistence;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogContext;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogSnapshot;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunContext;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在 Spring AI {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor} 语义基础上，
 * 适配 Alibaba ReAct：Agent 入口已将历史放入 graph 时，直接使用图内完整 messages 并持久化新增消息；
 * 未标记的旧调用路径仍在首轮合并记忆，避免工具循环每轮再次 prepend {@link ChatMemory#get} 导致重复。
 * <p>
 * 在 {@link #before} 内会将 {@link ChatMemory#CONVERSATION_ID} 写入请求 context（优先用户消息 metadata，
 * 其次当前线程 {@link #setConversationId} 绑定值）；流式场景下 Advisor 常在 {@code publishOn} 后执行，
 * ThreadLocal 可能不可用，故主路径仍依赖用户消息 metadata。
 */
@Slf4j
public final class ReactCompatibleMessageChatMemoryAdvisor implements BaseChatMemoryAdvisor {

    /**
     * 用户消息 metadata 键：回合开始时已由 {@link io.github.jerryt92.j2agent.service.llm.chat.ChatTurnLifecycle}
     * 预落库，Advisor 跳过重复 user add。
     */
    public static final String META_USER_MESSAGE_PRE_PERSISTED = "userMessagePrePersisted";

    /** 当前用户消息 metadata 键：Agent 入口已将窗口历史放入 graph，无须 Advisor 再次拼接。 */
    public static final String META_GRAPH_HISTORY_PREPARED = "graphHistoryPrepared";

    /**
     * 用户消息 metadata 键：标识委派子智能体调用；与直接聊天统一读取和持久化会话记忆。
     */
    public static final String META_SUB_AGENT_CALL_RUN = "subAgentCallRun";

    /**
     * @deprecated 仅用于读取历史消息 metadata；新代码请使用 {@link #META_SUB_AGENT_CALL_RUN}。
     */
    @Deprecated
    public static final String META_DELEGATE_RUN = "delegateRun";

    /**
     * 单次 Agent 调用内作为 conversationId 的辅助传递（与 {@link AgentRunContext#conversationId()} 一致）。
     */
    private static final ThreadLocal<String> THREAD_CONVERSATION_ID = new ThreadLocal<>();

    /**
     * 同一次 Advisor 调用内在 {@link #before} 解析出的会话键，供流式 {@link #after} 使用
     * （聚合后的 response context 往往不含 {@link ChatMemory#CONVERSATION_ID}）。
     */
    private static final ThreadLocal<String> ACTIVE_CONVERSATION_ID = new ThreadLocal<>();

    private final ChatMemory chatMemory;

    private final String defaultConversationId;

    private final int order;

    private final Scheduler scheduler;

    private ReactCompatibleMessageChatMemoryAdvisor(ChatMemory chatMemory, String defaultConversationId, int order,
                                                    Scheduler scheduler) {
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.hasText(defaultConversationId, "defaultConversationId cannot be null or empty");
        Assert.notNull(scheduler, "scheduler cannot be null");
        this.chatMemory = chatMemory;
        this.defaultConversationId = defaultConversationId;
        this.order = order;
        this.scheduler = scheduler;
    }

    /**
     * 绑定当前线程的会话键（入口与 {@link AgentRunContext#conversationId()} 一致）。
     */
    public static void setConversationId(String conversationId) {
        THREAD_CONVERSATION_ID.set(conversationId);
    }

    /**
     * 解除当前线程上的会话键绑定，避免线程池复用导致串会话。
     */
    public static void clear() {
        THREAD_CONVERSATION_ID.remove();
        ACTIVE_CONVERSATION_ID.remove();
    }

    /**
     * 读取当前线程绑定的会话键；未设置时返回 {@code null}。
     */
    private static String getThreadLocalConversationId() {
        return THREAD_CONVERSATION_ID.get();
    }

    /**
     * 判断当前 prompt 是否已包含模型侧消息（ReAct 工具链后续轮次）。
     */
    private static boolean containsAssistantMessage(List<Message> instructions) {
        return instructions.stream().anyMatch(m -> m instanceof AssistantMessage);
    }

    /**
     * 从本轮 prompt 的用户消息元数据中解析会话键（ReAct 多轮会保留首条用户消息）。
     */
    private static String extractConversationIdFromUserMessages(List<Message> instructions) {
        for (Message message : instructions) {
            if (message instanceof UserMessage userMessage) {
                Map<String, Object> metadata = userMessage.getMetadata();
                if (metadata == null) {
                    continue;
                }
                Object v = metadata.get(ChatMemory.CONVERSATION_ID);
                if (v != null && StringUtils.hasText(v.toString())) {
                    return v.toString();
                }
            }
        }
        return null;
    }

    /**
     * 若 context 尚未包含会话键，则写入 {@link ChatMemory#CONVERSATION_ID}，供 {@link #getConversationId} 使用。
     */
    private ChatClientRequest ensureConversationIdInContext(ChatClientRequest chatClientRequest) {
        if (chatClientRequest.context().containsKey(ChatMemory.CONVERSATION_ID)) {
            return chatClientRequest;
        }
        String id = extractConversationIdFromUserMessages(chatClientRequest.prompt().getInstructions());
        if (!StringUtils.hasText(id)) {
            id = getThreadLocalConversationId();
        }
        if (!StringUtils.hasText(id)) {
            log.warn("ChatMemory conversationId missing in request; skip memory for this turn");
            return chatClientRequest;
        }
        return chatClientRequest.mutate().context(ChatMemory.CONVERSATION_ID, id).build();
    }

    /**
     * 解析可落库的 composite 会话键；不使用 Spring 默认占位 id，避免 {@link ConversationIdCodec#parse} 失败。
     */
    private static String resolvePersistableConversationId(Map<String, Object> context, List<Message> instructions) {
        String id = null;
        if (context != null && context.containsKey(ChatMemory.CONVERSATION_ID)) {
            Object v = context.get(ChatMemory.CONVERSATION_ID);
            if (v != null) {
                id = v.toString();
            }
        }
        if (!isPersistableConversationId(id) && instructions != null) {
            id = extractConversationIdFromUserMessages(instructions);
        }
        if (!isPersistableConversationId(id)) {
            id = getThreadLocalConversationId();
        }
        if (!isPersistableConversationId(id)) {
            id = ACTIVE_CONVERSATION_ID.get();
        }
        return isPersistableConversationId(id) ? id : null;
    }

    private static boolean isPersistableConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId) || !conversationId.contains(":")) {
            return false;
        }
        try {
            ConversationIdCodec.parse(conversationId);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public Scheduler getScheduler() {
        return this.scheduler;
    }

    private static UserMessage findLastUserMessage(List<Message> instructions) {
        if (instructions == null || instructions.isEmpty()) {
            return null;
        }
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message message = instructions.get(i);
            if (message instanceof UserMessage userMessage) {
                return userMessage;
            }
        }
        return null;
    }

    /**
     * 预落库回合：用图内 user（含多模态 Media）覆盖 memory 末尾 user，避免 memory + instructions 重复拼接。
     */
    private static void replaceTrailingUserMessage(List<Message> messages, UserMessage liveUser) {
        if (messages == null || liveUser == null) {
            return;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                messages.set(i, liveUser);
                return;
            }
        }
        messages.add(liveUser);
    }

    /**
     * 预落库路径仅用 memory 拼 prompt，须保留图内 {@code instructions} 中的 {@link SystemMessage}
     * （如 ReactAgent {@code systemPrompt}），记忆库本身不落库 system。
     */
    private static void prependSystemMessageFromInstructions(List<Message> messages, List<Message> instructions) {
        SystemMessage systemMessage = findFirstSystemMessage(instructions);
        if (systemMessage == null || messages == null) {
            return;
        }
        messages.removeIf(SystemMessage.class::isInstance);
        messages.add(0, systemMessage);
    }

    private static SystemMessage findFirstSystemMessage(List<Message> instructions) {
        if (instructions == null) {
            return null;
        }
        for (Message message : instructions) {
            if (message instanceof SystemMessage systemMessage) {
                return systemMessage;
            }
        }
        return null;
    }

    private static boolean isUserMessagePrePersisted(List<Message> instructions) {
        return hasCurrentUserFlag(instructions, META_USER_MESSAGE_PRE_PERSISTED);
    }

    private static boolean hasCurrentUserFlag(List<Message> instructions, String flag) {
        UserMessage userMessage = findLastUserMessage(instructions);
        return userMessage != null && isTruthyMetadataFlag(userMessage.getMetadata().get(flag));
    }

    private static boolean isTruthyMetadataFlag(Object flag) {
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        ChatClientRequest requestWithConversationId = ensureConversationIdInContext(chatClientRequest);
        List<Message> instructions = requestWithConversationId.prompt().getInstructions();
        String conversationId = resolvePersistableConversationId(requestWithConversationId.context(), instructions);
        if (!StringUtils.hasText(conversationId)) {
            ACTIVE_CONVERSATION_ID.remove();
            log.warn("ChatMemory conversationId unresolved in before(); skip memory for this turn");
            return chatClientRequest;
        }
        ACTIVE_CONVERSATION_ID.set(conversationId);

        boolean graphHistoryPrepared = hasCurrentUserFlag(instructions, META_GRAPH_HISTORY_PREPARED);
        List<Message> processedMessages;
        if (graphHistoryPrepared || containsAssistantMessage(instructions)) {
            processedMessages = new ArrayList<>(instructions);
        } else if (isUserMessagePrePersisted(instructions)) {
            processedMessages = new ArrayList<>(this.chatMemory.get(conversationId));
            UserMessage liveUser = findLastUserMessage(instructions);
            if (liveUser != null) {
                replaceTrailingUserMessage(processedMessages, liveUser);
            }
            prependSystemMessageFromInstructions(processedMessages, instructions);
        } else {
            List<Message> memoryMessages = this.chatMemory.get(conversationId);
            processedMessages = new ArrayList<>(memoryMessages);
            processedMessages.addAll(instructions);
        }

        if (!graphHistoryPrepared) {
            for (int i = 0; i < processedMessages.size(); i++) {
                if (processedMessages.get(i) instanceof SystemMessage) {
                    Message systemMessage = processedMessages.remove(i);
                    processedMessages.add(0, systemMessage);
                    break;
                }
            }
        }

        ChatClientRequest processedChatClientRequest = requestWithConversationId.mutate()
                .prompt(requestWithConversationId.prompt().mutate().messages(processedMessages).build())
                .build();

        Message latestInput = processedChatClientRequest.prompt().getLastUserOrToolResponseMessage();
        boolean prePersistedUser = latestInput instanceof UserMessage && isUserMessagePrePersisted(instructions);
        if (latestInput != null && !prePersistedUser) {
            this.chatMemory.add(conversationId, latestInput);
        }

        return processedChatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        List<Message> assistantMessages = new ArrayList<>();
        if (chatClientResponse.chatResponse() != null) {
            assistantMessages = chatClientResponse.chatResponse()
                    .getResults()
                    .stream()
                    .map(g -> (Message) g.getOutput())
                    .toList();
        }
        try {
            String conversationId = resolvePersistableConversationId(chatClientResponse.context(), null);
            if (!StringUtils.hasText(conversationId)) {
                log.warn("ChatMemory conversationId unresolved in after(); skip persisting assistant messages");
                return chatClientResponse;
            }
            if (isTurnCancelledForConversation(conversationId)) {
                return chatClientResponse;
            }
            List<Message> toPersist = filterAssistantMessagesForPersistence(conversationId, assistantMessages);
            if (!toPersist.isEmpty()) {
                this.chatMemory.add(conversationId, toPersist);
            }
        } finally {
            ACTIVE_CONVERSATION_ID.remove();
        }
        return chatClientResponse;
    }

    /**
     * WebSocket 流式回合：纯文本 assistant 由 {@link io.github.jerryt92.j2agent.service.llm.ChatService}
     * 按 streamedContent/streamedReasoning 落库；此处仅保留 tool_calls 等结构化 assistant。
     */
    private static List<Message> filterAssistantMessagesForPersistence(String conversationId,
                                                                       List<Message> assistantMessages) {
        if (isTurnCancelledForConversation(conversationId)) {
            return List.of();
        }
        if (!StreamedAssistantPersistence.shouldSkipAdvisorTextAssistant(conversationId)) {
            return assistantMessages;
        }
        List<Message> toPersist = new ArrayList<>();
        for (Message message : assistantMessages) {
            if (message instanceof AssistantMessage assistantMessage
                    && !assistantMessage.hasToolCalls()
                    && StringUtils.hasText(assistantMessage.getText())) {
                continue;
            }
            toPersist.add(message);
        }
        return toPersist;
    }

    private static boolean isTurnCancelledForConversation(String conversationId) {
        AgentRunLogSnapshot snapshot = AgentRunLogContext.lookup(conversationId);
        if (snapshot == null || !StringUtils.hasText(snapshot.turnId())) {
            return false;
        }
        return ChatTurnCancellationRegistry.isCancelled(snapshot.turnId());
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        Scheduler scheduler = this.getScheduler();

        return Mono.just(chatClientRequest)
                .publishOn(scheduler)
                .map(request -> this.before(request, streamAdvisorChain))
                .flatMapMany(processedRequest -> {
                    String conversationId = ACTIVE_CONVERSATION_ID.get();
                    return streamAdvisorChain.nextStream(processedRequest)
                            .map(response -> enrichResponseContext(response, conversationId));
                })
                .transform(flux -> new ChatClientMessageAggregator().aggregateChatClientResponse(flux,
                        response -> this.after(response, streamAdvisorChain)))
                .doFinally(signalType -> {
                    ACTIVE_CONVERSATION_ID.remove();
                });
    }

    /**
     * 将 {@link #before} 解析的会话键写入每个流式 chunk 的 context，供聚合后 {@link #after} 与 Graph 使用。
     */
    private static ChatClientResponse enrichResponseContext(ChatClientResponse response,
                                                            String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return response;
        }
        var contextBuilder = new java.util.HashMap<>(response.context());
        boolean changed = false;
        if (StringUtils.hasText(conversationId)
                && !contextBuilder.containsKey(ChatMemory.CONVERSATION_ID)) {
            contextBuilder.put(ChatMemory.CONVERSATION_ID, conversationId);
            changed = true;
        }
        return changed ? response.mutate().context(contextBuilder).build() : response;
    }

    /**
     * 构建与 {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor} 一致的 Advisor。
     */
    public static Builder builder(ChatMemory chatMemory) {
        return new Builder(chatMemory);
    }

    /**
     * Builder，字段含义与 Spring AI 官方 {@code MessageChatMemoryAdvisor.Builder} 对齐。
     */
    public static final class Builder {

        private String conversationId = ChatMemory.DEFAULT_CONVERSATION_ID;

        private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

        private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

        private final ChatMemory chatMemory;

        private Builder(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public ReactCompatibleMessageChatMemoryAdvisor build() {
            return new ReactCompatibleMessageChatMemoryAdvisor(this.chatMemory, this.conversationId, this.order,
                    this.scheduler);
        }
    }
}
