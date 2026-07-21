package io.github.jerryt92.j2agent.service.llm.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.openai.api.OpenAiApi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmUsageExtractorTest {

    private final LlmUsageExtractor extractor = new LlmUsageExtractor(
            new ObjectMapper(),
            new BillableTokenCalculator());

    @Test
    void shouldExtractOpenAiCachedAndReasoningTokens() {
        OpenAiApi.Usage nativeUsage = new OpenAiApi.Usage(
                25,
                100,
                125,
                new OpenAiApi.Usage.PromptTokensDetails(3, 40),
                new OpenAiApi.Usage.CompletionTokenDetails(7, null, 5, null));

        LlmUsageSnapshot snapshot = extractor.extract(new DefaultUsage(100, 25, 125, nativeUsage));

        assertEquals("AVAILABLE", snapshot.getUsageStatus());
        assertEquals(100, snapshot.getInputTokens());
        assertEquals(25, snapshot.getOutputTokens());
        assertEquals(125, snapshot.getTotalTokens());
        assertEquals(40, snapshot.getCachedInputTokens());
        assertEquals(40, snapshot.getCacheReadInputTokens());
        assertEquals(7, snapshot.getReasoningOutputTokens());
        assertEquals(3, snapshot.getAudioInputTokens());
        assertEquals(5, snapshot.getAudioOutputTokens());
        assertEquals(125, snapshot.getBillableTokenCount());
        assertTrue(snapshot.getNativeUsageJson().contains("cached_tokens"));
    }

    @Test
    void shouldExtractAnthropicCacheReadAndCreationTokens() {
        AnthropicApi.Usage nativeUsage = new AnthropicApi.Usage(120, 30, 50, 70);

        LlmUsageSnapshot snapshot = extractor.extract(new DefaultUsage(120, 30, 150, nativeUsage));

        assertEquals("AVAILABLE", snapshot.getUsageStatus());
        assertEquals(120, snapshot.getInputTokens());
        assertEquals(30, snapshot.getOutputTokens());
        assertEquals(150, snapshot.getTotalTokens());
        assertEquals(70, snapshot.getCachedInputTokens());
        assertEquals(70, snapshot.getCacheReadInputTokens());
        assertEquals(50, snapshot.getCacheCreationInputTokens());
        assertEquals(150, snapshot.getBillableTokenCount());
        assertTrue(snapshot.getNativeUsageJson().contains("cache_read_input_tokens"));
    }

    @Test
    void shouldUseStandardUsageForOllamaLikeUsageWithoutNativeDetails() {
        LlmUsageSnapshot snapshot = extractor.extract(new DefaultUsage(80, 20, 100));

        assertEquals("AVAILABLE", snapshot.getUsageStatus());
        assertEquals(80, snapshot.getInputTokens());
        assertEquals(20, snapshot.getOutputTokens());
        assertEquals(100, snapshot.getTotalTokens());
        assertEquals(0, snapshot.getCachedInputTokens());
        assertEquals(0, snapshot.getCacheReadInputTokens());
        assertEquals(0, snapshot.getCacheCreationInputTokens());
        assertEquals(100, snapshot.getBillableTokenCount());
    }
}
