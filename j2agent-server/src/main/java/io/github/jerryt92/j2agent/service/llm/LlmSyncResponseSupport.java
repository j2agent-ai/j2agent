package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.config.provider.LlmActiveConfig;
import io.github.jerryt92.j2agent.service.llm.reasoning.AssistantMessageReasoningExtractor;
import io.github.jerryt92.j2agent.service.llm.reasoning.SpringAiReasoningMetadataAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.core.io.Resource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 同步 LLM 调用响应解析与诊断日志。
 */
@Slf4j
public final class LlmSyncResponseSupport {

    private LlmSyncResponseSupport() {
    }

    /**
     * 从同步 {@link ChatResponse} 提取可用助手文本。
     * <p>深度思考开启时（尤其 Anthropic），响应常含多个 Generation：thinking/redacted 在前、最终回答在后。
     * 仅读 {@link ChatResponse#getResult()} 会在首块无正文时得到 null，故遍历全部 Generation：
     * 优先取非 thinking 块的正文；否则回退到 reasoning / thinking 文本（OpenAI 空 content + reasoningContent 等）。
     */
    public static String extractAssistantText(ChatResponse response) {
        if (response == null || CollectionUtils.isEmpty(response.getResults())) {
            return null;
        }
        String fallbackReasoning = null;
        for (Generation generation : response.getResults()) {
            if (generation == null || generation.getOutput() == null) {
                continue;
            }
            AssistantMessage output = generation.getOutput();
            ChatGenerationMetadata metadata = generation.getMetadata();
            // thinking / redacted 块不当作最终回答，仅作无答案时的回退
            if (SpringAiReasoningMetadataAdapter.isThinkingContentMessage(output)) {
                if (!StringUtils.hasText(fallbackReasoning)) {
                    String reasoning = AssistantMessageReasoningExtractor.extractFullReasoning(output, metadata);
                    if (!StringUtils.hasText(reasoning)) {
                        reasoning = output.getText();
                    }
                    if (StringUtils.hasText(reasoning)) {
                        fallbackReasoning = reasoning.trim();
                    }
                }
                continue;
            }
            if (StringUtils.hasText(output.getText())) {
                return output.getText().trim();
            }
            String reasoning = AssistantMessageReasoningExtractor.extractFullReasoning(output, metadata);
            if (StringUtils.hasText(reasoning)) {
                return reasoning.trim();
            }
        }
        return fallbackReasoning;
    }

    public static void logMultimodalRequest(LlmActiveConfig cfg, List<Media> media) {
        if (cfg == null) {
            log.info("llm sync multimodal request, provider=unknown, model=unknown, media=[{}]",
                    describeMedia(media));
            return;
        }
        log.info("llm sync multimodal request, provider={}, model={}, media=[{}]",
                cfg.getProviderType(), cfg.getModelName(), describeMedia(media));
    }

    public static void logEmptyMultimodalResponse(LlmActiveConfig cfg, List<Media> media, ChatResponse response) {
        String provider = cfg == null ? "unknown" : cfg.getProviderType();
        String model = cfg == null ? "unknown" : cfg.getModelName();
        log.warn(
                "llm sync multimodal returned empty text, provider={}, model={}, media=[{}], {}",
                provider, model, describeMedia(media), summarizeEmptyResponseDiagnostics(response));
    }

    /**
     * 空响应诊断摘要（不含图片/正文内容），便于区分 API 真无 text vs 解析丢失。
     */
    static String summarizeEmptyResponseDiagnostics(ChatResponse response) {
        if (response == null) {
            return "diagnostics={response=null}";
        }
        AssistantMessage output = response.getResult() != null ? response.getResult().getOutput() : null;
        ChatGenerationMetadata generationMetadata = response.getResult() != null
                ? response.getResult().getMetadata()
                : null;
        boolean hasToolCalls = output != null && output.hasToolCalls();
        boolean hasReasoning = output != null
                && AssistantMessageReasoningExtractor.hasReasoningSignal(output, generationMetadata);
        String finishReason = resolveFinishReason(response);
        Integer outputTokens = resolveOutputTokens(response);
        List<String> generationMetaKeys = collectGenerationMetadataKeys(response);
        List<String> assistantMetaKeys = output != null && output.getMetadata() != null
                ? new ArrayList<>(output.getMetadata().keySet())
                : List.of();
        List<String> assistantMetaTypes = collectAssistantMetadataTypes(output);
        int outputTextLen = output != null && output.getText() != null ? output.getText().length() : 0;
        int generationCount = response.getResults() != null ? response.getResults().size() : 0;
        return "diagnostics={"
                + "finishReason=" + finishReason
                + ", outputTokens=" + outputTokens
                + ", generationCount=" + generationCount
                + ", generationMetaKeys=" + generationMetaKeys
                + ", assistantMetaKeys=" + assistantMetaKeys
                + ", assistantMetaTypes=" + assistantMetaTypes
                + ", outputTextLen=" + outputTextLen
                + ", hasToolCalls=" + hasToolCalls
                + ", hasReasoning=" + hasReasoning
                + "}";
    }

    static String resolveFailureCauseLabel(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 16) {
            String className = current.getClass().getSimpleName();
            if (className.contains("ReadTimeout")) {
                return "ReadTimeout";
            }
            if (className.contains("WriteTimeout")) {
                return "WriteTimeout";
            }
            if (current.getCause() == null || current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return error.getClass().getSimpleName();
    }

    private static Integer resolveOutputTokens(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata == null) {
            return null;
        }
        Usage usage = metadata.getUsage();
        if (usage == null) {
            return null;
        }
        return usage.getCompletionTokens();
    }

    private static List<String> collectGenerationMetadataKeys(ChatResponse response) {
        if (response.getResults() == null) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Generation generation : response.getResults()) {
            if (generation == null || generation.getMetadata() == null) {
                continue;
            }
            keys.addAll(generation.getMetadata().keySet());
        }
        return new ArrayList<>(keys);
    }

    private static List<String> collectAssistantMetadataTypes(AssistantMessage output) {
        if (output == null || output.getMetadata() == null) {
            return List.of();
        }
        Map<String, Object> metadata = output.getMetadata();
        List<String> types = new ArrayList<>();
        Object type = metadata.get("type");
        if (type != null && StringUtils.hasText(type.toString())) {
            types.add(type.toString());
        }
        Object blockType = metadata.get("blockType");
        if (blockType != null && StringUtils.hasText(blockType.toString())) {
            types.add(blockType.toString());
        }
        if (Boolean.TRUE.equals(metadata.get("thinking"))) {
            types.add("thinking-flag");
        }
        if (metadata.containsKey("signature")) {
            types.add("signature");
        }
        return types;
    }

    private static String resolveFinishReason(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return "none";
        }
        for (Generation generation : response.getResults()) {
            if (generation == null || generation.getMetadata() == null) {
                continue;
            }
            Object finishReason = generation.getMetadata().get("finishReason");
            if (finishReason == null) {
                finishReason = generation.getMetadata().get("finish-reason");
            }
            if (finishReason != null && StringUtils.hasText(finishReason.toString())) {
                return finishReason.toString();
            }
        }
        return "unknown";
    }

    static String describeMedia(List<Media> media) {
        if (media == null || media.isEmpty()) {
            return "";
        }
        return media.stream()
                .map(LlmSyncResponseSupport::describeOneMedia)
                .collect(Collectors.joining(", "));
    }

    private static String describeOneMedia(Media item) {
        if (item == null) {
            return "null";
        }
        String mime = item.getMimeType() != null ? item.getMimeType().toString() : "unknown";
        return mime + ":" + mediaByteSize(item) + "B";
    }

    private static long mediaByteSize(Media item) {
        Object data = item.getData();
        if (data instanceof Resource resource) {
            try {
                long length = resource.contentLength();
                if (length >= 0) {
                    return length;
                }
            } catch (IOException ignored) {
                // fall through
            }
            try (var input = resource.getInputStream()) {
                return input.readAllBytes().length;
            } catch (IOException e) {
                return 0;
            }
        }
        if (data instanceof byte[] bytes) {
            return bytes.length;
        }
        return 0;
    }
}
