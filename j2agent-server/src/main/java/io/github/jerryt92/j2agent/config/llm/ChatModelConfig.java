package io.github.jerryt92.j2agent.config.llm;

import io.github.jerryt92.j2agent.config.provider.ActiveProviderHolder;
import io.github.jerryt92.j2agent.service.llm.usage.LlmUsageExtractor;
import io.github.jerryt92.j2agent.service.llm.usage.LlmUsageRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * ChatModel 装配：主对话 {@link ReloadableRoutingChatModel} 标注 {@code @Primary}；
 * 短同步调用统一走 {@link io.github.jerryt92.j2agent.service.llm.LlmSyncService}。
 */
@Configuration
public class ChatModelConfig {

    @Autowired
    private ActiveProviderHolder activeProviderHolder;
    @Autowired
    private LlmUsageExtractor usageExtractor;
    @Autowired
    private LlmUsageRecorder usageRecorder;

    /**
     * 实现按当前 LLM 配置在 OpenAI 兼容 / vLLM / Anthropic / Ollama / LM Studio 间路由，并支持热更新。
     */
    @Primary
    @Bean
    public ReloadableRoutingChatModel openAiChatModel() {
        return new ReloadableRoutingChatModel(activeProviderHolder, usageExtractor, usageRecorder);
    }
}
