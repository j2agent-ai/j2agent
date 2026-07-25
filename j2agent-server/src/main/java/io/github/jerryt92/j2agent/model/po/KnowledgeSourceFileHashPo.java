package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

/**
 * 知识库源文件哈希树持久化实体。
 */
@Data
public class KnowledgeSourceFileHashPo {
    /**
     * 主键（UUIDv7）。
     */
    private String id;
    private String filePath;
    private String filePathHash;
    private String fileSha256;
    /**
     * 匹配知识库高级配置的哈希。
     */
    private String metadataConfigHash;
    private String collectionName;
    /**
     * Milvus 分区名列表 JSON（空配置为 null）。
     */
    private String partitionNamesJson;
    /**
     * 该文件产出的知识条数。
     */
    private Integer knowledgeCount;
    /**
     * 该文件大小（字节）。
     */
    private Long fileSizeBytes;
    private Long lastScanTime;
    private String syncStatus;
    private Long createdAt;
    private Long updatedAt;
}
