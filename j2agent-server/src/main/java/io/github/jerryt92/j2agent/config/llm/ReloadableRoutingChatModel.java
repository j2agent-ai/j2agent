package io.github.jerryt92.j2agent.config.llm;

import io.github.jerryt92.j2agent.service.llm.LlmProviderErrorFormatter;
import io.github.jerryt92.j2agent.service.llm.PromptConversationIdExtractor;
import io.github.jerryt92.j2agent.service.llm.ThinkingOverrideRegistry;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.reasoning.AssistantMessageReasoningExtractor;
import io.github.jerryt92.j2agent.config.provider.ActiveProviderHolder;
import io.github.jerryt92.j2agent.config.provider.LlmActiveConfig;
import io.github.jerryt92.j2agent.service.llm.usage.LlmUsageCapturingChatModel;
import io.github.jerryt92.j2agent.service.llm.usage.LlmUsageExtractor;
import io.github.jerryt92.j2agent.service.llm.usage.LlmUsageRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对外统一为 {@link ChatModel}：内部按 {@link ActiveProviderHolder#getActiveLlm()} 持有具体实现，
 * 支持热更新后 {@link #reload()} 无重启切换底层实例。
 */
@Slf4j
public class ReloadableRoutingChatModel implements ChatModel {

    private static final int EMPTY_STREAM_MAX_RETRIES = 3;

    private final ActiveProviderHolder activeProviderHolder;
    private final LlmUsageExtractor usageExtractor;
    private final LlmUsageRecorder usageRecorder;
    private final AtomicReference<ChatModel> delegate = new AtomicReference<>();

    public ReloadableRoutingChatModel(ActiveProviderHolder activeProviderHolder,
                                      LlmUsageExtractor usageExtractor,
                                      LlmUsageRecorder usageRecorder) {
        this.activeProviderHolder = activeProviderHolder;
        this.usageExtractor = usageExtractor;
        this.usageRecorder = usageRecorder;
        reload();
    }

    /**
     * 使用当前内存中的 {@link LlmActiveConfig} 重建底层 ChatModel；构建失败时清空 delegate，
     * 后续调用会以异常方式提示运维修复配置，避免静默使用旧实例。
     */
    public synchronized void reload() {
        LlmActiveConfig cfg = activeProviderHolder.getActiveLlm();
        try {
            ChatModel next = wrap(LlmBackedChatModelFactory.build(cfg));
            delegate.set(next);
            log.info("ChatModel reloaded: provider={}, model={}",
                    cfg == null ? "none" : cfg.getProviderType(),
                    cfg == null ? "none" : cfg.getModelName());
        } catch (Exception e) {
            delegate.set(null);
            log.error("ChatModel reload 失败：当前 LLM 配置不可用", e);
        }
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return resolveDelegate(prompt, AgentThinkingOverride.USE_PROVIDER_DEFAULT).call(prompt);
    }

    /**
     * 显式指定覆盖策略的同步调用（无 Prompt 会话键时使用）。
     */
    public ChatResponse call(Prompt prompt, AgentThinkingOverride thinkingOverride) {
        return resolveDelegate(prompt, thinkingOverride).call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return stream(prompt, AgentThinkingOverride.USE_PROVIDER_DEFAULT);
    }

    /**
     * 显式指定覆盖策略的流式调用（无 Prompt 会话键时使用）。
     * 空流重试在单次 LLM HTTP 调用粒度进行，避免 Agent 轮次内已有输出时无法由 ChatService 整流重试。
     */
    public Flux<ChatResponse> stream(Prompt prompt, AgentThinkingOverride thinkingOverride) {
        AtomicInteger emptyStreamRetryNo = new AtomicInteger(0);
        return streamOnce(prompt, thinkingOverride)
                .retryWhen(Retry.backoff(EMPTY_STREAM_MAX_RETRIES, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(3))
                        .filter(LlmProviderErrorFormatter::isEmptyStreamFailure)
                        .doBeforeRetry(rs -> {
                            LlmActiveConfig cfg = activeProviderHolder.getActiveLlm();
                            int nextRetryNo = emptyStreamRetryNo.incrementAndGet();
                            log.warn("LLM stream empty, willRetry single call. nextRetryNo={}, provider={}, model={}",
                                    nextRetryNo,
                                    cfg == null ? "none" : cfg.getProviderType(),
                                    cfg == null ? "none" : cfg.getModelName());
                        }));
    }

    private Flux<ChatResponse> streamOnce(Prompt prompt, AgentThinkingOverride thinkingOverride) {
        return Flux.defer(() -> resolveDelegate(prompt, thinkingOverride).stream(prompt))
                .filter(ReloadableRoutingChatModel::hasUsableGeneration)
                .switchIfEmpty(Flux.defer(() -> Flux.error(createEmptyStreamException())));
    }

    private IllegalStateException createEmptyStreamException() {
        LlmActiveConfig cfg = activeProviderHolder.getActiveLlm();
        String provider = cfg == null ? "none" : cfg.getProviderType();
        String model = cfg == null ? "none" : cfg.getModelName();
        log.error("LLM stream returned no usable chunks, provider={}, model={}", provider, model);
        return new IllegalStateException(
                "LLM 流式响应为空（无有效 token），当前 provider=" + provider + ", model=" + model
                        + "。请核对 baseUrl、API Key、模型名是否在对应平台可用。");
    }

    /**
     * 保留含文本或 tool_calls 的块；Anthropic 在工具流式阶段会发出空 generations，需与 Graph 过滤语义一致。
     */
    private static boolean hasUsableGeneration(ChatResponse response) {
        if (response == null || CollectionUtils.isEmpty(response.getResults())) {
            return false;
        }
        response.getResult();
        AssistantMessage output = response.getResult().getOutput();
        return output.hasToolCalls()
                || StringUtils.hasText(output.getText())
                || hasReasoningContent(response);
    }

    private static boolean hasReasoningContent(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return false;
        }
        return AssistantMessageReasoningExtractor.hasReasoningSignal(
                response.getResult().getOutput(),
                response.getResult().getMetadata());
    }

    private ChatModel requireDelegate() {
        ChatModel current = delegate.get();
        if (current == null) {
            throw new IllegalStateException("LLM 当前无可用配置，请在「设置 → LLM 接口」中启用一项");
        }
        return current;
    }

    /**
     * 解析当前 LLM 调用应使用的 ChatModel：优先显式参数，其次运行时注册表，否则热更新缓存实例。
     */
    private ChatModel resolveDelegate(Prompt prompt, AgentThinkingOverride explicitOverride) {
        AgentThinkingOverride effective = resolveEffectiveOverride(prompt, explicitOverride);
        if (effective != null && effective.overridesProvider()) {
            LlmActiveConfig cfg = activeProviderHolder.getActiveLlm();
            return wrap(LlmBackedChatModelFactory.build(cfg, effective));
        }
        return requireDelegate();
    }

    private ChatModel wrap(ChatModel chatModel) {
        return new LlmUsageCapturingChatModel(
                chatModel,
                activeProviderHolder::getActiveLlm,
                usageExtractor,
                usageRecorder,
                "CHAT");
    }

    /**
     * 合并显式覆盖与 {@link ThinkingOverrideRegistry} 中按 conversationId 绑定的本轮策略。
     */
    private static AgentThinkingOverride resolveEffectiveOverride(Prompt prompt,
                                                                  AgentThinkingOverride explicitOverride) {
        if (explicitOverride != null && explicitOverride.overridesProvider()) {
            return explicitOverride;
        }
        String conversationId = PromptConversationIdExtractor.extract(prompt);
        if (conversationId != null) {
            AgentThinkingOverride registered = ThinkingOverrideRegistry.get(conversationId);
            if (registered != null) {
                return registered;
            }
        }
        return explicitOverride != null ? explicitOverride : AgentThinkingOverride.USE_PROVIDER_DEFAULT;
    }
}
