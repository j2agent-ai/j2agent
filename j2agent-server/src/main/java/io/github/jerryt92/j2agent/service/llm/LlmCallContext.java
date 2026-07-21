package io.github.jerryt92.j2agent.service.llm;

import org.springframework.util.StringUtils;

/**
 * 在一次短同步 LLM 调用期间传递会话键，供 usage 捕获器归属到当前 turn。
 */
public final class LlmCallContext {

    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();

    private LlmCallContext() {
    }

    public static String conversationId() {
        return CONVERSATION_ID.get();
    }

    public static <T> T withConversationId(String conversationId, SupplierWithException<T> supplier) {
        if (!StringUtils.hasText(conversationId)) {
            return supplier.get();
        }
        String previous = CONVERSATION_ID.get();
        CONVERSATION_ID.set(conversationId);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                CONVERSATION_ID.remove();
            } else {
                CONVERSATION_ID.set(previous);
            }
        }
    }

    public interface SupplierWithException<T> {
        T get();
    }
}
