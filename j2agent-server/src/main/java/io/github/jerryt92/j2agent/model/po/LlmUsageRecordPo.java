package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

@Data
public class LlmUsageRecordPo {
    private String id;
    private String userId;
    private String contextId;
    private String agentId;
    private String turnId;
    private Integer callSeq;
    private String callKind;
    private String providerConfigId;
    private String providerType;
    private String modelName;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Integer billableTokenCount;
    private Integer cachedInputTokens;
    private Integer cacheReadInputTokens;
    private Integer cacheCreationInputTokens;
    private Integer reasoningOutputTokens;
    private Integer audioInputTokens;
    private Integer audioOutputTokens;
    private String usageStatus;
    private String nativeUsageJson;
    private String errorMessage;
    private Long createTime;
}
