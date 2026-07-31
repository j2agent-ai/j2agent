package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

/**
 * Token 用量按用户聚合查询行。
 */
@Data
public class LlmUsageSummaryRow {
    private String userId;
    private String username;
    private Long callCount;
    private Long inputTokens;
    private Long outputTokens;
    private Long billableTokens;
}
