package io.github.jerryt92.j2agent.service.llm.usage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TurnUsageAccumulator {

    private static final Map<String, TurnUsageContext> CONTEXTS = new ConcurrentHashMap<>();

    private TurnUsageAccumulator() {
    }

    public static void bind(TurnUsageContext context) {
        if (context != null && context.getConversationId() != null) {
            CONTEXTS.put(context.getConversationId(), context);
        }
    }

    public static TurnUsageContext get(String conversationId) {
        return conversationId == null ? null : CONTEXTS.get(conversationId);
    }

    public static void clear(String conversationId) {
        if (conversationId != null) {
            CONTEXTS.remove(conversationId);
        }
    }
}
