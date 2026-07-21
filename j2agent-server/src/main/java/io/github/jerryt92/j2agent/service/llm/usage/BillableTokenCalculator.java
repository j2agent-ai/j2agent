package io.github.jerryt92.j2agent.service.llm.usage;

import org.springframework.stereotype.Component;

@Component
public class BillableTokenCalculator {

    public int calculate(LlmUsageSnapshot usage) {
        if (usage == null || !usage.available()) {
            return 0;
        }
        Integer totalTokens = usage.getTotalTokens();
        if (totalTokens != null) {
            return Math.max(totalTokens, 0);
        }
        return Math.max(nvl(usage.getInputTokens()) + nvl(usage.getOutputTokens()), 0);
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
