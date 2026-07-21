package io.github.jerryt92.j2agent.service.llm.usage;

import lombok.Value;

import java.util.concurrent.atomic.AtomicInteger;

@Value
public class TurnUsageContext {
    String contextId;
    String agentId;
    String turnId;
    String conversationId;
    String userId;
    AtomicInteger callSeq = new AtomicInteger(0);

    public int nextCallSeq() {
        return callSeq.incrementAndGet();
    }
}
