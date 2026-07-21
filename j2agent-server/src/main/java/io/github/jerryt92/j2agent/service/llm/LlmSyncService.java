package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.config.llm.LlmBackedChatModelFactory;
import io.github.jerryt92.j2agent.config.llm.LlmSyncTimeouts;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.config.provider.ActiveProviderHolder;
import io.github.jerryt92.j2agent.config.provider.LlmActiveConfig;
import io.github.jerryt92.j2agent.config.provider.LlmThinkingSupport;
import io.github.jerryt92.j2agent.service.llm.usage.LlmUsageCapturingChatModel;
import io.github.jerryt92.j2agent.service.llm.usage.LlmUsageExtractor;
import io.github.jerryt92.j2agent.service.llm.usage.LlmUsageRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 平台统一的同步 LLM 服务：持有与当前所选提供商一致的 {@link ChatModel}，
 * 供 RAG Query 改写等短同步调用使用（强制关闭深度思考，不读取会话级 thinking 覆盖）。
 */
@Slf4j
@Service
public class LlmSyncService {

    private final ActiveProviderHolder activeProviderHolder;
    private final LlmUsageExtractor usageExtractor;
    private final LlmUsageRecorder usageRecorder;
    private final AtomicReference<ChatModel> textChatModel = new AtomicReference<>();
    private final AtomicReference<ChatModel> visionChatModel = new AtomicReference<>();

    @Autowired
    public LlmSyncService(ActiveProviderHolder activeProviderHolder,
                          LlmUsageExtractor usageExtractor,
                          LlmUsageRecorder usageRecorder) {
        this.activeProviderHolder = activeProviderHolder;
        this.usageExtractor = usageExtractor;
        this.usageRecorder = usageRecorder;
        reload();
    }

    LlmSyncService(ActiveProviderHolder activeProviderHolder) {
        this(activeProviderHolder, null, null);
    }

    /**
     * 按当前 LLM 配置重建文本与视觉两个 ChatModel（均 OFF）。
     */
    public synchronized void reload() {
        LlmActiveConfig cfg = activeProviderHolder.getActiveLlm();
        try {
            if (cfg == null) {
                textChatModel.set(null);
                visionChatModel.set(null);
                log.warn("llm sync service reload: no active LLM configuration");
                return;
            }
            textChatModel.set(wrap(LlmBackedChatModelFactory.build(cfg, syncThinkingOverride()), "SYNC"));
            visionChatModel.set(wrap(LlmBackedChatModelFactory.build(cfg, syncThinkingOverride()), "SYNC_VISION"));
            log.info("llm sync service reloaded: provider={}, model={}, thinking=off",
                    cfg.getProviderType(), cfg.getModelName());
        } catch (Exception e) {
            textChatModel.set(null);
            visionChatModel.set(null);
            log.error("llm sync service reload failed", e);
        }
    }

    public LlmActiveConfig activeLlm() {
        return activeProviderHolder.getActiveLlm();
    }

    public ChatModel chatModel() {
        return requireTextChatModel();
    }

    public ChatClient.Builder chatClientBuilder() {
        return ChatClient.builder(requireTextChatModel());
    }

    public ChatResponse call(Prompt prompt) {
        return requireTextChatModel().call(prompt);
    }

    public ChatResponse call(Prompt prompt, String conversationId) {
        return LlmCallContext.withConversationId(conversationId, () -> call(prompt));
    }

    public String callAssistantText(Prompt prompt) {
        return LlmSyncResponseSupport.extractAssistantText(call(prompt));
    }

    public String callAssistantText(Prompt prompt, String conversationId) {
        return LlmCallContext.withConversationId(conversationId, () -> callAssistantText(prompt));
    }

    /**
     * 同步多模态 user 调用（文字 + 图片），用于 RAG Query 改写等场景。
     * 按 provider 在 {@link ChatOptions} 层再次显式关闭深度思考。
     */
    public String callUserMultimodal(String userTextPrompt, List<Media> media, int maxTokens) {
        return callUserMultimodal(userTextPrompt, media, maxTokens, null);
    }

    public String callUserMultimodal(String userTextPrompt, List<Media> media, int maxTokens, String conversationId) {
        LlmSyncResponseSupport.logMultimodalRequest(activeLlm(), media);
        try {
            return LlmCallContext.withConversationId(conversationId, () -> {
                ChatClient chatClient = ChatClient.builder(requireVisionChatModel()).build();
                StringBuilder textBuilder = new StringBuilder();
                chatClient.prompt()
                        .options(LlmThinkingSupport.buildSyncCallOptions(
                                activeLlm(), 0.0, maxTokens, syncThinkingOverride()))
                        .user(userSpec -> {
                            if (StringUtils.hasText(userTextPrompt)) {
                                userSpec.text(userTextPrompt);
                            }
                            if (media != null) {
                                media.forEach(userSpec::media);
                            }
                        })
                        .stream()
                        .chatResponse()
                        .mapNotNull(LlmSyncResponseSupport::extractAssistantText)
                        .filter(StringUtils::hasText)
                        .doOnNext(textBuilder::append)
                        .blockLast(LlmSyncTimeouts.responseReadTimeout());
                String text = textBuilder.toString().trim();
                if (!StringUtils.hasText(text)) {
                    log.warn("llm sync multimodal returned empty text after stream, provider={}, model={}, media=[{}]",
                            activeLlm() == null ? "unknown" : activeLlm().getProviderType(),
                            activeLlm() == null ? "unknown" : activeLlm().getModelName(),
                            LlmSyncResponseSupport.describeMedia(media));
                }
                return StringUtils.hasText(text) ? text : null;
            });
        } catch (RuntimeException e) {
            log.warn("llm sync multimodal call failed, thinkingPolicy=off, cause={}, detail={}",
                    LlmSyncResponseSupport.resolveFailureCauseLabel(e),
                    LlmProviderErrorFormatter.formatForLog(e));
            return null;
        }
    }

    static AgentThinkingOverride syncThinkingOverride() {
        return AgentThinkingOverride.OFF;
    }

    private ChatModel requireTextChatModel() {
        return requireChatModel(textChatModel);
    }

    /**
     * Vision 调用专用 ChatModel：构建时 OFF；Multimodal 请求另通过 {@link LlmThinkingSupport#buildSyncCallOptions}
     * 按 Anthropic / Ollama / OpenAI 兼容分别显式关闭深度思考。
     */
    private ChatModel requireVisionChatModel() {
        LlmActiveConfig cfg = activeLlm();
        if (cfg != null && !supportsExplicitThinkingOff(cfg.getProviderType())) {
            log.debug("llm sync vision model: provider={} has no thinking toggle, rely on default options",
                    cfg.getProviderType());
        }
        return requireChatModel(visionChatModel);
    }

    private static boolean supportsExplicitThinkingOff(String providerType) {
        return LlmThinkingSupport.supports(providerType);
    }

    private static ChatModel requireChatModel(AtomicReference<ChatModel> holder) {
        ChatModel current = holder.get();
        if (current == null) {
            throw new IllegalStateException("同步 LLM 当前无可用配置，请在「设置 → LLM 接口」中启用一项");
        }
        return current;
    }

    private ChatModel wrap(ChatModel chatModel, String callKind) {
        if (usageExtractor == null || usageRecorder == null) {
            return chatModel;
        }
        return new LlmUsageCapturingChatModel(
                chatModel,
                activeProviderHolder::getActiveLlm,
                usageExtractor,
                usageRecorder,
                callKind);
    }
}
