package io.github.jerryt92.j2agent.config.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 当前生效的 LLM 配置；字段是各 provider 的并集，未使用字段保持默认值。
 *
 * <p>同时承载原属于 {@code llm-*} 扁平键的全局运行时参数（如 temperature）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class LlmActiveConfig {
    /** api_provider_config.id */
    private String id;

    /** 提供商类型，决定运行时如何构建 ChatModel */
    private String providerType;

    /** 模型名称 */
    private String modelName;
    /** 服务地址 */
    private String baseUrl;
    /** API Key（用作 Authorization）；Ollama 可空 */
    private String apiKey;

    /** OpenAI 兼容 / vLLM 的 completions 路径 */
    private String completionsPath;

    /** Ollama 模型驻留时间（秒） */
    private Integer keepAliveSeconds;
    /** Ollama 上下文长度（num_ctx）；仅 Ollama 运行时使用 */
    private Integer contextLength;

    /** Anthropic 单次回复最大输出 token（Messages API max_tokens）；Anthropic 必填 */
    private Integer maxTokens;

    /** 采样温度，0~2 */
    private Double temperature;

    /** 深度思考模式：provider_default / on / off；仅 Anthropic、LM Studio、Ollama 生效 */
    private String thinkingMode;

    /** Anthropic thinking.budget_tokens 或 LM Studio reasoning_tokens（thinkingMode=on）；未填时用 {@link LlmThinkingSupport#DEFAULT_THINKING_BUDGET} */
    private Integer thinkingBudgetTokens;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getCompletionsPath() {
        return completionsPath;
    }

    public void setCompletionsPath(String completionsPath) {
        this.completionsPath = completionsPath;
    }

    public Integer getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(Integer keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public Integer getContextLength() {
        return contextLength;
    }

    public void setContextLength(Integer contextLength) {
        this.contextLength = contextLength;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public String getThinkingMode() {
        return thinkingMode;
    }

    public void setThinkingMode(String thinkingMode) {
        this.thinkingMode = thinkingMode;
    }

    public Integer getThinkingBudgetTokens() {
        return thinkingBudgetTokens;
    }

    public void setThinkingBudgetTokens(Integer thinkingBudgetTokens) {
        this.thinkingBudgetTokens = thinkingBudgetTokens;
    }
}
