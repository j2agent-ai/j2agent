package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import com.alibaba.fastjson2.JSON;
import io.github.jerryt92.j2agent.mapper.KnowledgeSourceFileHashMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeSourceFileHashPo;
import io.github.jerryt92.j2agent.utils.HashUtil;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库哈希树持久化服务。
 */
@Slf4j
@Service
public class KnowledgeRepoHashTreeService {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private final KnowledgeSourceFileHashMapper mapper;

    public KnowledgeRepoHashTreeService(KnowledgeSourceFileHashMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 从数据库加载根目录哈希快照。
     */
    public Map<String, String> loadSnapshot() {
        List<KnowledgeSourceFileHashPo> records = mapper.selectAll();
        Map<String, String> snapshot = new HashMap<>();
        for (KnowledgeSourceFileHashPo record : records) {
            if (STATUS_ACTIVE.equals(record.getSyncStatus())) {
                snapshot.put(record.getFilePath(), KnowledgeRepoDiffHash.build(
                        record.getFileSha256(), record.getMetadataConfigHash(), record.getCollectionName()));
            }
        }
        return snapshot;
    }

    /**
     * 加载 ACTIVE 文件到 collection 的映射。
     */
    public Map<String, String> loadActiveFileCollections() {
        List<Map<String, Object>> rows = mapper.selectActiveFileCollectionMap();
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object filePath = row.get("filePath");
            Object collectionName = row.get("collectionName");
            if (filePath != null && collectionName != null) {
                result.put(filePath.toString(), collectionName.toString());
            }
        }
        return result;
    }

    /**
     * 加载各 collection 的 ACTIVE 文件计数。
     */
    public Map<String, Long> loadActiveCollectionCounts() {
        List<Map<String, Object>> rows = mapper.selectActiveCollectionCounts();
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object collectionName = row.get("collectionName");
            Object fileCount = row.get("fileCount");
            if (collectionName == null || fileCount == null) {
                continue;
            }
            result.put(collectionName.toString(), ((Number) fileCount).longValue());
        }
        return result;
    }

    /**
     * 写入或更新 ACTIVE 文件状态。
     */
    /**
     * 写入或更新 ACTIVE 文件状态（含 Milvus 分区配置 JSON）。
     */
    public void upsertActive(Path filePath, String sha256, String metadataConfigHash, String collectionName, List<String> partitionNames, int knowledgeCount, long fileSizeBytes, long scanTime) {
        KnowledgeSourceFileHashPo po = new KnowledgeSourceFileHashPo();
        String relativePath = normalizeRepoRelativePath(filePath);
        // 使用 UUIDv7 作为主键，避免自增主键在分布式场景下的冲突。
        po.setId(UUIDv7Utils.randomUUIDv7());
        po.setFilePath(relativePath);
        po.setFilePathHash(calculatePathHash(relativePath));
        po.setFileSha256(sha256);
        po.setMetadataConfigHash(metadataConfigHash);
        po.setCollectionName(collectionName);
        po.setPartitionNamesJson(partitionNames == null || partitionNames.isEmpty() ? null : JSON.toJSONString(partitionNames));
        po.setKnowledgeCount(knowledgeCount);
        po.setFileSizeBytes(fileSizeBytes);
        po.setLastScanTime(scanTime);
        po.setSyncStatus(STATUS_ACTIVE);
        po.setCreatedAt(scanTime);
        po.setUpdatedAt(scanTime);
        mapper.upsert(po);
    }

    /**
     * 将文件标记为删除状态。
     */
    public void markDeleted(Path filePath, long scanTime) {
        mapper.markDeleted(calculatePathHash(normalizeRepoRelativePath(filePath)), scanTime);
    }

    /**
     * 清空全部哈希记录，供完全重建使用。
     */
    public void deleteAll() {
        int deleted = mapper.deleteAll();
        log.warn("已清空 knowledge_source_file_hash，删除行数={}", deleted);
    }

    /**
     * 计算路径哈希，避免超长索引。
     */
    private String calculatePathHash(String filePath) {
        try {
            return HashUtil.getMessageDigest(filePath.getBytes(StandardCharsets.UTF_8), HashUtil.MdAlgorithm.SHA256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("计算文件路径哈希失败", e);
        }
    }

    /**
     * 归一化仓库内相对路径，确保跨系统一致性。
     */
    private String normalizeRepoRelativePath(Path filePath) {
        String normalized = filePath.normalize().toString();
        return normalized.replace("\\", "/");
    }

}
