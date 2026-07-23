package io.github.jerryt92.j2agent.model.repository;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 知识库仓库 API 数据传输对象。
 */
public final class KnowledgeRepositoryDtos {
    private KnowledgeRepositoryDtos() {
    }

    @Data
    public static class ListResponse {
        private List<Item> data;
    }

    @Data
    public static class Item {
        private String id;
        private String repoCode;
        private String type;
        private String protocol;
        private Boolean enabled;
        private Boolean readonly;
        private String localPath;
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
        private Map<String, Object> protocolConfig;
        private Boolean hasCredential;
        private List<String> collections;
        private String displayName;
        private Map<String, String> collectionAliases;
        private Integer minHeadingLevel;
        private Boolean filenameAsTitle;
    }

    @Data
    public static class UpsertRequest {
        private String repoCode;
        private String protocol;
        private Boolean enabled;
        private Integer updateIntervalMinutes;
        private String remoteUrl;
        private String defaultBranch;
        private Map<String, Object> protocolConfig;
        private String displayName;
        private Map<String, String> collectionAliases;
        private CredentialConfig credentialConfig;
    }

    @Data
    public static class CredentialConfig {
        private String username;
        private String password;
        private String token;
        private String accessKey;
        private String secretKey;
    }

    @Data
    public static class SyncResponse {
        private Boolean success;
        private String message;
    }
}
