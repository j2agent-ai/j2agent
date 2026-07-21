package io.github.jerryt92.j2agent.config.llm;

import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.config.provider.LlmActiveConfig;
import io.github.jerryt92.j2agent.config.provider.LlmThinkingSupport;
import io.github.jerryt92.j2agent.config.provider.ProviderTypes;
import io.micrometer.observation.ObservationRegistry;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 根据 {@link LlmActiveConfig} 装配 Spring AI 的 {@link ChatModel}。
 *
 * <p>支持 OpenAI 兼容、vLLM（OpenAI 兼容协议）、Anthropic、Ollama、LM Studio 五种 provider。
 */
public final class LlmBackedChatModelFactory {

    /** vLLM / LM Studio 默认的 chat completions path（OpenAI 兼容协议） */
    private static final String VLLM_DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** LM Studio 默认服务地址 */
    private static final String LM_STUDIO_DEFAULT_BASE_URL = "http://127.0.0.1:1234";

    private LlmBackedChatModelFactory() {
    }

    /**
     * 按 {@code providerType} 选择底层实现；config 为空时抛出异常以避免静默使用上次实例。
     */
    public static ChatModel build(LlmActiveConfig config) {
        return build(config, null);
    }

    /**
     * 按 providerType 构建 ChatModel，并可叠加 Agent 级深度思考覆盖。
     */
    public static ChatModel build(LlmActiveConfig config, AgentThinkingOverride thinkingOverride) {
        if (config == null) {
            throw new IllegalStateException("无可用的 LLM 配置：请在「设置 → LLM 接口」中选择一个启用项设为当前");
        }
        String provider = StringUtils.isBlank(config.getProviderType())
                ? ProviderTypes.LLM_OPEN_AI
                : config.getProviderType().trim();
        return switch (provider) {
            case ProviderTypes.LLM_OPEN_AI -> buildOpenAi(config, false);
            case ProviderTypes.LLM_VLLM -> buildOpenAi(config, true);
            case ProviderTypes.LLM_ANTHROPIC -> buildAnthropic(config, thinkingOverride);
            case ProviderTypes.LLM_OLLAMA -> buildOllama(config, thinkingOverride);
            case ProviderTypes.LLM_LM_STUDIO -> buildLmStudio(config, thinkingOverride);
            default -> throw new IllegalStateException("不支持的 LLM provider: " + provider);
        };
    }

    /**
     * OpenAI 兼容 Chat API（DashScope、vLLM 等均通过此分支）。
     *
     * @param vllmDefault vLLM 时若 completionsPath 未填，使用 {@value #VLLM_DEFAULT_COMPLETIONS_PATH}
     */
    public static OpenAiChatModel buildOpenAi(LlmActiveConfig cfg, boolean vllmDefault) {
        String completionsPath = cfg.getCompletionsPath();
        if (StringUtils.isBlank(completionsPath) && vllmDefault) {
            completionsPath = VLLM_DEFAULT_COMPLETIONS_PATH;
        }
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey() == null ? "" : cfg.getApiKey())
                .webClientBuilder(LlmReactiveHttpClientFactory.createWebClientBuilder("llm-openai"));
        if (StringUtils.isNotBlank(completionsPath)) {
            apiBuilder.completionsPath(completionsPath);
        }
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(cfg.getModelName())
                .streamUsage(true);
        if (cfg.getTemperature() != null) {
            optionsBuilder.temperature(cfg.getTemperature());
        }
        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    /**
     * LM Studio OpenAI 兼容 Chat API；{@code baseUrl} / {@code completionsPath} 未填时使用本地默认值。
     */
    public static OpenAiChatModel buildLmStudio(LlmActiveConfig cfg, AgentThinkingOverride thinkingOverride) {
        String baseUrl = StringUtils.isNotBlank(cfg.getBaseUrl()) ? cfg.getBaseUrl() : LM_STUDIO_DEFAULT_BASE_URL;
        String completionsPath = StringUtils.isNotBlank(cfg.getCompletionsPath())
                ? cfg.getCompletionsPath()
                : VLLM_DEFAULT_COMPLETIONS_PATH;
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(cfg.getApiKey() == null ? "" : cfg.getApiKey())
                .completionsPath(completionsPath)
                .webClientBuilder(LlmReactiveHttpClientFactory.createWebClientBuilder("llm-lm-studio"));
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(cfg.getModelName())
                .streamUsage(true);
        if (cfg.getTemperature() != null) {
            optionsBuilder.temperature(cfg.getTemperature());
        }
        LlmThinkingSupport.applyLmStudio(optionsBuilder, cfg, thinkingOverride);
        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    /**
     * Anthropic Messages API；{@code baseUrl} 留空时使用 SDK 默认。
     */
    public static AnthropicChatModel buildAnthropic(LlmActiveConfig cfg, AgentThinkingOverride thinkingOverride) {
        AnthropicApi.Builder apiBuilder = AnthropicApi.builder()
                .apiKey(cfg.getApiKey() == null ? "" : cfg.getApiKey())
                .restClientBuilder(LlmReactiveHttpClientFactory.createRestClientBuilder("llm-anthropic"))
                .webClientBuilder(LlmReactiveHttpClientFactory.createWebClientBuilder("llm-anthropic"));
        if (StringUtils.isNotBlank(cfg.getBaseUrl())) {
            apiBuilder.baseUrl(cfg.getBaseUrl());
        }
        int maxTokens = cfg.getMaxTokens() != null && cfg.getMaxTokens() > 0 ? cfg.getMaxTokens() : 16384;
        AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder()
                .model(cfg.getModelName())
                .maxTokens(maxTokens);
        if (cfg.getTemperature() != null) {
            optionsBuilder.temperature(cfg.getTemperature());
        }
        LlmThinkingSupport.applyAnthropic(optionsBuilder, cfg, thinkingOverride);
        return AnthropicChatModel.builder()
                .anthropicApi(apiBuilder.build())
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    /**
     * Ollama 本地或远程服务；apiKey 可空，非空时以 Bearer Header 发送。
     */
    public static OllamaChatModel buildOllama(LlmActiveConfig cfg, AgentThinkingOverride thinkingOverride) {
        WebClient.Builder webClientBuilder = LlmReactiveHttpClientFactory.createWebClientBuilder("llm-ollama");
        if (StringUtils.isNotBlank(cfg.getApiKey())) {
            webClientBuilder = webClientBuilder.defaultHeader("Authorization", "Bearer " + cfg.getApiKey());
        }
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(cfg.getBaseUrl())
                .webClientBuilder(webClientBuilder)
                .build();
        int keepAliveSeconds = cfg.getKeepAliveSeconds() == null ? 0 : Math.max(cfg.getKeepAliveSeconds(), 0);
        String keepAlive = keepAliveSeconds + "s";
        OllamaChatOptions.Builder optionsBuilder = OllamaChatOptions.builder()
                .model(cfg.getModelName())
                .keepAlive(keepAlive);
        if (cfg.getContextLength() != null && cfg.getContextLength() > 0) {
            optionsBuilder.numCtx(cfg.getContextLength());
        }
        if (cfg.getTemperature() != null) {
            optionsBuilder.temperature(cfg.getTemperature());
        }
        LlmThinkingSupport.applyOllama(optionsBuilder, cfg, thinkingOverride);
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(optionsBuilder.build())
                .observationRegistry(ObservationRegistry.NOOP)
                .modelManagementOptions(ModelManagementOptions.defaults())
                .build();
    }
}
