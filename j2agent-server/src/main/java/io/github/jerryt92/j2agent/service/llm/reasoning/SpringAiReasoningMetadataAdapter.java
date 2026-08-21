package io.github.jerryt92.j2agent.service.llm.reasoning;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 将 Spring AI 各 {@code ChatModel} 产出的异构 metadata，适配为本项目统一的深度思考字段
 * {@link #UNIFIED_REASONING_KEY}（{@code reasoningContent}）。
 *
 * <h2>背景：Spring AI 未统一 reasoning API</h2>
 * <p>Spring AI 1.1.x 在调用层统一了 {@code ChatModel} / {@code AssistantMessage} / {@code ChatResponse}，
 * 但<strong>没有</strong>提供类似 {@code AssistantMessage.getReasoningContent()} 的跨提供商 API。
 * 各 {@code *ChatModel} 把 Provider 原生 thinking 映射进同一套消息类型时，采用不同的 metadata 约定。
 * 业务层若只读 {@code metadata.get("reasoningContent")}，会在 Anthropic / Ollama 等路径上丢失或误判思考内容。</p>
 *
 * <h2>已知 Spring AI metadata 形态（按形态适配，不按 providerType 分支）</h2>
 * <table>
 *   <tr><th>形态</th><th>典型来源</th><th>读取方式</th></tr>
 *   <tr><td>{@code metadata["reasoningContent"]}</td><td>OpenAI 兼容</td><td>metadata 字符串，流式为 per-chunk 增量</td></tr>
 *   <tr><td>{@code metadata["thinking"] == true} + {@code getText()}</td><td>Anthropic 流式</td><td>正文在 content，布尔 flag 标记 thinking 块</td></tr>
 *   <tr><td>{@code metadata["signature"]} + {@code getText()}</td><td>Anthropic 非流式</td><td>正文即 thinking block</td></tr>
 *   <tr><td>{@code Generation.metadata["thinking"]}</td><td>Ollama 流式</td><td>Generation 层累积字符串，需 {@link ReasoningSnapshotTracker} 转 delta</td></tr>
 *   <tr><td>{@code metadata["type"] == "thinking"} + {@code getText()}</td><td>Ollama 部分路径</td><td>正文即 thinking</td></tr>
 * </table>
 *
 * <p>本类是唯一应直接解析 Provider metadata 形态的入口；上层 {@link AssistantMessageReasoningExtractor}
 * 负责流式 tracker / XML chunk 切分，并委托本类完成 metadata → {@link #UNIFIED_REASONING_KEY} 转换。</p>
 *
 * @see AssistantMessageReasoningExtractor
 */
public final class SpringAiReasoningMetadataAdapter {

    /**
     * 本项目与 {@code MessageDto}、持久化 {@code meta_json} 统一的深度思考字段名
     */
    public static final String UNIFIED_REASONING_KEY = "reasoningContent";

    /**
     * metadata 中 per-chunk 增量键（OpenAI 兼容流式）
     */
    private static final String[] DELTA_KEYS = {"reasoningContent", "reasoning_content"};

    private static final String META_TYPE = "type";
    /**
     * thinking 块类型标记值
     */
    private static final String TYPE_THINKING = "thinking";
    /**
     * thinking 布尔标记键（Anthropic 流式）或累积字符串键（Ollama）
     */
    private static final String META_THINKING = "thinking";
    /**
     * Anthropic 非流式 thinking block 签名键
     */
    private static final String META_SIGNATURE = "signature";
    /**
     * Anthropic redacted thinking 数据键（无可见正文，应跳过）
     */
    private static final String META_DATA = "data";

    /**
     * 从 Provider metadata 适配出的统一深度思考片段。
     *
     * @param reasoningContent 写入 {@link #UNIFIED_REASONING_KEY} 的文本；流式场景可能为 delta 或累积快照
     * @param cumulative       是否为累积快照（需经 {@link ReasoningSnapshotTracker} 转 delta）
     * @param thinkingBlock    是否来自 thinking 块（正文在 {@code AssistantMessage#getText()}）
     */
    public record AdaptedReasoning(String reasoningContent, boolean cumulative, boolean thinkingBlock) {
    }

    private SpringAiReasoningMetadataAdapter() {
    }

    /**
     * 流式 chunk：从 {@link AssistantMessage} + 可选 {@link ChatGenerationMetadata} 适配统一 reasoning 字段。
     */
    public static AdaptedReasoning adaptStreamChunk(AssistantMessage message,
                                                    ChatGenerationMetadata generationMetadata) {
        if (message == null) {
            return null;
        }
        Map<String, Object> metadata = message.getMetadata() != null ? message.getMetadata() : Map.of();
        String text = blankToNull(message.getText());

        if (isThinkingBlock(metadata)) {
            return new AdaptedReasoning(text, false, true);
        }
        if (metadata.containsKey(META_SIGNATURE) && text != null) {
            return new AdaptedReasoning(text, false, true);
        }
        if (shouldSkipChunk(metadata, message, text)) {
            return null;
        }

        String delta = readProviderDelta(metadata);
        if (delta == null && generationMetadata != null) {
            delta = readProviderDelta(generationMetadata);
        }
        if (delta != null) {
            return new AdaptedReasoning(delta, false, false);
        }

        String cumulative = readProviderCumulative(metadata);
        if (cumulative == null && generationMetadata != null) {
            cumulative = readProviderCumulative(generationMetadata);
        }
        if (cumulative != null) {
            return new AdaptedReasoning(cumulative, true, false);
        }
        return null;
    }

    /**
     * 非流式 / 历史回放：适配完整统一 reasoning 文本。
     */
    public static String adaptFullReasoning(AssistantMessage message,
                                            ChatGenerationMetadata generationMetadata) {
        AdaptedReasoning adapted = adaptStreamChunk(message, generationMetadata);
        if (adapted != null && adapted.thinkingBlock()) {
            return adapted.reasoningContent();
        }
        if (message == null) {
            return null;
        }
        Map<String, Object> metadata = message.getMetadata() != null ? message.getMetadata() : Map.of();

        String delta = readProviderDelta(metadata);
        if (delta == null) {
            delta = readProviderCumulative(metadata);
        }
        if (delta == null && generationMetadata != null) {
            delta = readProviderDelta(generationMetadata);
            if (delta == null) {
                delta = readProviderCumulative(generationMetadata);
            }
        }
        return delta;
    }

    /**
     * 当前 chunk 是否携带可识别的深度思考 metadata 信号（用于流式过滤空块）。
     */
    public static boolean hasReasoningMetadata(AssistantMessage message,
                                               ChatGenerationMetadata generationMetadata) {
        return adaptStreamChunk(message, generationMetadata) != null
                || (adaptFullReasoning(message, generationMetadata) != null);
    }

    /**
     * 从 message metadata 读取 per-chunk 增量 reasoning（如 OpenAI {@code reasoningContent}）。
     */
    static String readProviderDelta(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        for (String key : DELTA_KEYS) {
            String value = asNonBlankString(metadata.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 从 {@link ChatGenerationMetadata} 读取 per-chunk 增量 reasoning。
     */
    static String readProviderDelta(ChatGenerationMetadata metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        for (String key : DELTA_KEYS) {
            if (metadata.containsKey(key)) {
                String value = asNonBlankString(metadata.get(key));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * 从 message metadata 读取累积 reasoning 快照（如 Ollama {@code thinking} 字符串）。
     */
    static String readProviderCumulative(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object thinking = metadata.get(META_THINKING);
        if (thinking instanceof String s && StringUtils.hasText(s)) {
            return s;
        }
        return readFuzzyEntries(metadata.entrySet());
    }

    /**
     * 从 {@link ChatGenerationMetadata} 读取累积 reasoning 快照。
     */
    static String readProviderCumulative(ChatGenerationMetadata metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object thinking = metadata.get(META_THINKING);
        if (thinking instanceof String s && StringUtils.hasText(s)) {
            return s;
        }
        return readFuzzyEntries(metadata.entrySet());
    }

    /**
     * 是否为 thinking 块（正文在 content，metadata 仅作类型标记）。
     */
    static boolean isThinkingBlock(Map<String, Object> metadata) {
        return Boolean.TRUE.equals(metadata.get(META_THINKING))
                || TYPE_THINKING.equals(metadata.get(META_TYPE));
    }

    /**
     * 判断当前 {@link AssistantMessage} 是否为深度思考块（非最终回答）。
     * <p>Anthropic 非流式会把 thinking / redacted_thinking / text 拆成多个 Generation，
     * 同步取文案时应跳过此类块，避免只读 {@code ChatResponse#getResult()} 首块导致空结果。
     */
    public static boolean isThinkingContentMessage(AssistantMessage message) {
        if (message == null) {
            return false;
        }
        Map<String, Object> metadata = message.getMetadata() != null ? message.getMetadata() : Map.of();
        if (isThinkingBlock(metadata) || metadata.containsKey(META_SIGNATURE)) {
            return true;
        }
        // redacted_thinking：仅有 data、无可见正文
        return metadata.containsKey(META_DATA)
                && !StringUtils.hasText(message.getText())
                && !message.hasToolCalls();
    }

    /**
     * 是否应跳过当前 chunk（redacted 空块、仅 signature 无正文等）。
     */
    static boolean shouldSkipChunk(Map<String, Object> metadata,
                                   AssistantMessage message,
                                   String text) {
        if (metadata.containsKey(META_DATA) && text == null && !message.hasToolCalls()) {
            return true;
        }
        return metadata.containsKey(META_SIGNATURE) && text == null;
    }

    /**
     * 模糊匹配键名含 reason/thinking 的字符串值（兜底）。
     */
    private static String readFuzzyEntries(Iterable<Map.Entry<String, Object>> entries) {
        for (Map.Entry<String, Object> entry : entries) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lower = key.toLowerCase();
            if (!lower.contains("reason") && !lower.contains("thinking")) {
                continue;
            }
            // thinking=true 布尔标记已在 isThinkingBlock 处理，此处只认字符串
            if (META_THINKING.equals(key) && !(entry.getValue() instanceof String)) {
                continue;
            }
            String value = asNonBlankString(entry.getValue());
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String asNonBlankString(Object value) {
        if (value == null || value instanceof Boolean) {
            return null;
        }
        String s = value.toString();
        return StringUtils.hasText(s) ? s : null;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
