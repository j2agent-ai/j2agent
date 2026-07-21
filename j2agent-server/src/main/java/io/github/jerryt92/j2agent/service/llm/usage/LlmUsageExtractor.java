package io.github.jerryt92.j2agent.service.llm.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Component
public class LlmUsageExtractor {

    private final ObjectMapper objectMapper;
    private final BillableTokenCalculator billableTokenCalculator;

    public LlmUsageExtractor(ObjectMapper objectMapper, BillableTokenCalculator billableTokenCalculator) {
        this.objectMapper = objectMapper;
        this.billableTokenCalculator = billableTokenCalculator;
    }

    public LlmUsageSnapshot extract(Usage usage) {
        if (usage == null) {
            return LlmUsageSnapshot.unavailable("metadata.usage is null");
        }
        if (usage.getClass().getName().endsWith("EmptyUsage")) {
            return LlmUsageSnapshot.unavailable("metadata.usage is empty");
        }
        Object nativeUsage = usage.getNativeUsage();
        LlmUsageSnapshot base = nativeUsage == null
                ? extractStandard(usage)
                : extractNative(usage, nativeUsage);
        return base.toBuilder()
                .billableTokenCount(billableTokenCalculator.calculate(base))
                .build();
    }

    private LlmUsageSnapshot extractStandard(Usage usage) {
        return LlmUsageSnapshot.builder()
                .usageStatus("AVAILABLE")
                .inputTokens(usage.getPromptTokens())
                .outputTokens(usage.getCompletionTokens())
                .totalTokens(usage.getTotalTokens())
                .cachedInputTokens(0)
                .cacheReadInputTokens(0)
                .cacheCreationInputTokens(0)
                .nativeUsageJson(null)
                .build();
    }

    private LlmUsageSnapshot extractNative(Usage usage, Object nativeUsage) {
        String className = nativeUsage.getClass().getName();
        try {
            if (className.contains("openai")) {
                return extractOpenAi(usage, nativeUsage);
            }
            if (className.contains("anthropic")) {
                return extractAnthropic(usage, nativeUsage);
            }
        } catch (RuntimeException e) {
            log.warn("failed to parse native llm usage: type={}", className, e);
            return extractStandard(usage).toBuilder()
                    .nativeUsageJson(toJson(nativeUsage))
                    .errorMessage("native usage parse failed: " + e.getClass().getSimpleName())
                    .build();
        }
        return extractStandard(usage).toBuilder()
                .nativeUsageJson(toJson(nativeUsage))
                .build();
    }

    private LlmUsageSnapshot extractOpenAi(Usage usage, Object nativeUsage) {
        Object promptDetails = invoke(nativeUsage, "promptTokensDetails");
        Object completionDetails = invoke(nativeUsage, "completionTokenDetails");
        Integer cachedTokens = intValue(invoke(promptDetails, "cachedTokens"));
        Integer audioInputTokens = intValue(invoke(promptDetails, "audioTokens"));
        Integer reasoningTokens = intValue(invoke(completionDetails, "reasoningTokens"));
        Integer audioOutputTokens = intValue(invoke(completionDetails, "audioTokens"));
        return LlmUsageSnapshot.builder()
                .usageStatus("AVAILABLE")
                .inputTokens(firstInt(invoke(nativeUsage, "promptTokens"), usage.getPromptTokens()))
                .outputTokens(firstInt(invoke(nativeUsage, "completionTokens"), usage.getCompletionTokens()))
                .totalTokens(firstInt(invoke(nativeUsage, "totalTokens"), usage.getTotalTokens()))
                .cachedInputTokens(nvl(cachedTokens))
                .cacheReadInputTokens(nvl(cachedTokens))
                .cacheCreationInputTokens(0)
                .reasoningOutputTokens(nvl(reasoningTokens))
                .audioInputTokens(nvl(audioInputTokens))
                .audioOutputTokens(nvl(audioOutputTokens))
                .nativeUsageJson(toJson(nativeUsage))
                .build();
    }

    private LlmUsageSnapshot extractAnthropic(Usage usage, Object nativeUsage) {
        Integer inputTokens = firstInt(invoke(nativeUsage, "inputTokens"), usage.getPromptTokens());
        Integer outputTokens = firstInt(invoke(nativeUsage, "outputTokens"), usage.getCompletionTokens());
        return LlmUsageSnapshot.builder()
                .usageStatus("AVAILABLE")
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(nvl(inputTokens) + nvl(outputTokens))
                .cachedInputTokens(nvl(firstInt(invoke(nativeUsage, "cacheReadInputTokens"), 0)))
                .cacheReadInputTokens(nvl(firstInt(invoke(nativeUsage, "cacheReadInputTokens"), 0)))
                .cacheCreationInputTokens(nvl(firstInt(invoke(nativeUsage, "cacheCreationInputTokens"), 0)))
                .nativeUsageJson(toJson(nativeUsage))
                .build();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"" + value.getClass().getName() + "\"}";
        }
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Integer firstInt(Object first, Integer fallback) {
        Integer value = intValue(first);
        return value != null ? value : fallback;
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
