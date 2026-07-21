package io.github.jerryt92.j2agent.service.llm.usage;

import io.github.jerryt92.j2agent.config.provider.LlmActiveConfig;
import io.github.jerryt92.j2agent.service.llm.LlmCallContext;
import io.github.jerryt92.j2agent.service.llm.PromptConversationIdExtractor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class LlmUsageCapturingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final Supplier<LlmActiveConfig> activeConfigSupplier;
    private final LlmUsageExtractor usageExtractor;
    private final LlmUsageRecorder usageRecorder;
    private final String callKind;

    public LlmUsageCapturingChatModel(ChatModel delegate,
                                      Supplier<LlmActiveConfig> activeConfigSupplier,
                                      LlmUsageExtractor usageExtractor,
                                      LlmUsageRecorder usageRecorder,
                                      String callKind) {
        this.delegate = delegate;
        this.activeConfigSupplier = activeConfigSupplier;
        this.usageExtractor = usageExtractor;
        this.usageRecorder = usageRecorder;
        this.callKind = callKind;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        record(resolveConversationId(prompt), extractUsage(response), null);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String conversationId = resolveConversationId(prompt);
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        AtomicBoolean recorded = new AtomicBoolean(false);
        return delegate.stream(prompt)
                .doOnNext(response -> {
                    Usage usage = extractUsage(response);
                    if (usage != null) {
                        lastUsage.set(usage);
                    }
                })
                .doOnError(error -> recordUnavailableOnce(conversationId, recorded, error))
                .doOnComplete(() -> {
                    Usage usage = lastUsage.get();
                    if (usage == null) {
                        recordUnavailableOnce(conversationId, recorded, null);
                    } else if (recorded.compareAndSet(false, true)) {
                        record(conversationId, usage, null);
                    }
                });
    }

    private void recordUnavailableOnce(String conversationId, AtomicBoolean recorded, Throwable error) {
        if (recorded.compareAndSet(false, true)) {
            String message = error == null ? "stream completed without usage" : error.getClass().getSimpleName();
            usageRecorder.record(conversationId, callKind, activeConfigSupplier.get(), LlmUsageSnapshot.unavailable(message));
        }
    }

    private void record(String conversationId, Usage usage, String errorMessage) {
        LlmUsageSnapshot snapshot = usage == null
                ? LlmUsageSnapshot.unavailable(errorMessage == null ? "metadata.usage is null" : errorMessage)
                : usageExtractor.extract(usage);
        usageRecorder.record(conversationId, callKind, activeConfigSupplier.get(), snapshot);
    }

    private static Usage extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    private static String resolveConversationId(Prompt prompt) {
        String conversationId = PromptConversationIdExtractor.extract(prompt);
        return StringUtils.hasText(conversationId) ? conversationId : LlmCallContext.conversationId();
    }
}
