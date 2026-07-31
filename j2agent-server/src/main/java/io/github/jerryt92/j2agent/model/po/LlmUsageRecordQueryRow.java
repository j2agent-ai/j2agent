package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

/**
 * Token 用量明细查询行（含用户名）。
 */
@Data
public class LlmUsageRecordQueryRow {
    private String id;
    private String userId;
    private String username;
    private String contextId;
    private String agentId;
    private String turnId;
    private Integer callSeq;
    private String callKind;
    private String providerType;
    private String modelName;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Integer billableTokenCount;
    private String usageStatus;
    private Long createTime;
}
