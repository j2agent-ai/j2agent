package io.github.jerryt92.j2agent.service.rag.knowledge;

import io.github.jerryt92.j2agent.mapper.KnowledgeTextChunkMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeTextChunkPo;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeTextChunkParser;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库逻辑文本块 MySQL 读写服务。
 */
@Service
public class KnowledgeTextChunkService {
    private final KnowledgeTextChunkMapper mapper;

    public KnowledgeTextChunkService(KnowledgeTextChunkMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 批量 upsert 逻辑文本块。
     */
    public void batchUpsert(List<KnowledgeTextChunkParser.TextChunk> chunks,
                            String sourceFile,
                            String fileSha256,
                            String collectionName) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (KnowledgeTextChunkParser.TextChunk chunk : chunks) {
            KnowledgeTextChunkPo po = new KnowledgeTextChunkPo();
            po.setId(chunk.textChunkId());
            po.setHeadingPath(chunk.headingPath());
            po.setTextChunk(chunk.textChunk());
            po.setSourceFile(sourceFile);
            po.setCollectionName(collectionName);
            po.setFileSha256(fileSha256);
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            mapper.upsert(po);
        }
    }

    /**
     * 按 ID 批量查询，返回 id → po 映射。
     */
    public Map<String, KnowledgeTextChunkPo> getByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> distinctIds = ids.stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
        if (distinctIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, KnowledgeTextChunkPo> result = new LinkedHashMap<>();
        var repoCodes = io.github.jerryt92.j2agent.service.security.KnowledgeReadScope.repositoryCodes();
        List<KnowledgeTextChunkPo> rows;
        if (repoCodes == null) rows = mapper.selectByIds(distinctIds);
        else if (repoCodes.isEmpty()) rows = List.of();
        else rows = mapper.selectByIdsInRepositories(distinctIds, repoCodes);
        for (KnowledgeTextChunkPo po : rows) {
            result.put(po.getId(), po);
        }
        return result;
    }

    /**
     * 按 collection 分页查询。
     */
    public List<KnowledgeTextChunkPo> listByCollection(String collectionName, String search, int offset, int limit) {
        if (StringUtils.isBlank(collectionName)) {
            return List.of();
        }
        String normalizedSearch = StringUtils.trimToNull(search);
        var repos = io.github.jerryt92.j2agent.service.security.KnowledgeReadScope.repositoryCodes();
        if (repos != null) {
            if (repos.isEmpty()) return List.of();
            return mapper.selectByCollectionInRepositories(collectionName, normalizedSearch, repos,
                    Math.max(offset, 0), Math.max(1, Math.min(limit, 1000)));
        }
        return mapper.selectByCollection(collectionName, normalizedSearch, Math.max(offset, 0), Math.max(limit, 1));
    }

    /**
     * 删除指定源文件的全部逻辑文本块。
     */
    public void deleteBySourceFile(String sourceFile) {
        if (StringUtils.isBlank(sourceFile)) {
            return;
        }
        mapper.deleteBySourceFile(sourceFile);
    }

    /**
     * 清空全部逻辑文本块，供完全重建使用。
     */
    public void deleteAll() {
        mapper.deleteAll();
    }
}
