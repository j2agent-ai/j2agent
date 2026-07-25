package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

/**
 * 可扩展知识库仓库配置。
 */
@Data
public class KnowledgeRepositoryPo {
    private String id;
    private String repoCode;
    private String type;
    private String protocol;
    private Boolean enabled;
    private Integer updateIntervalMinutes;
    private String status;
    private String remoteUrl;
    private String defaultBranch;
    private String lastRevision;
    private String lastRevisionMessage;
    private String lastRevisionAuthor;
    private Long lastRevisionTime;
    private Long lastSyncTime;
    private String lastError;
    private String protocolConfig;
    private String displayName;
    private String metadataConfig;
    private String credentialConfigCipher;
    private Long createdAt;
    private Long updatedAt;
}
