package io.github.jerryt92.j2agent.service.llm.usage;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class LlmUsageSnapshot {
    String usageStatus;
    Integer inputTokens;
    Integer outputTokens;
    Integer totalTokens;
    Integer billableTokenCount;
    Integer cachedInputTokens;
    Integer cacheReadInputTokens;
    Integer cacheCreationInputTokens;
    Integer reasoningOutputTokens;
    Integer audioInputTokens;
    Integer audioOutputTokens;
    String nativeUsageJson;
    String errorMessage;

    public boolean available() {
        return "AVAILABLE".equals(usageStatus);
    }

    public static LlmUsageSnapshot unavailable(String errorMessage) {
        return LlmUsageSnapshot.builder()
                .usageStatus("UNAVAILABLE")
                .errorMessage(errorMessage)
                .billableTokenCount(0)
                .build();
    }
}
