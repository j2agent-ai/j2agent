package io.github.jerryt92.j2agent.service.rag.knowledge;

import io.github.jerryt92.j2agent.model.KnowledgeCollectionDto;
import io.github.jerryt92.j2agent.model.KnowledgeCollectionListDto;
import io.github.jerryt92.j2agent.model.KnowledgeDto;
import io.github.jerryt92.j2agent.model.KnowledgeGetListDto;
import io.github.jerryt92.j2agent.model.po.KnowledgeTextChunkPo;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoHashTreeService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repository.KnowledgeRepositoryService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库查询服务：提供 collection 列表与文本块查询。
 */
@Slf4j
@Service
public class KnowledgeService {
    private final KnowledgeTextChunkService knowledgeTextChunkService;
    private final EmbeddingService embeddingService;
    private final KnowledgeRepoMetadataService metadataService;
    private final KnowledgeRepoHashTreeService hashTreeService;
    private final KnowledgeRepositoryService repositoryService;

    public KnowledgeService(KnowledgeTextChunkService knowledgeTextChunkService,
                            EmbeddingService embeddingService,
                            KnowledgeRepoMetadataService metadataService,
                            KnowledgeRepoHashTreeService hashTreeService,
                            KnowledgeRepositoryService repositoryService) {
        this.knowledgeTextChunkService = knowledgeTextChunkService;
        this.embeddingService = embeddingService;
        this.metadataService = metadataService;
        this.hashTreeService = hashTreeService;
        this.repositoryService = repositoryService;
    }

    /**
     * 查询当前可用的知识库 collection 列表（含展示名等元信息）。
     */
    public KnowledgeCollectionListDto getKnowledgeCollections() {
        KnowledgeRepositoryDtos.ListResponse repositories = repositoryService.list();
        List<KnowledgeRepositoryDtos.Item> repositoryItems = repositories == null || repositories.getData() == null
                ? List.of()
                : repositories.getData();
        Set<String> collections = collectConfiguredAndActiveCollections();
        KnowledgeCollectionListDto result = new KnowledgeCollectionListDto();
        result.setData(buildCollectionDtos(repositoryItems, collections));
        return result;
    }

    /**
     * 将仓库元数据与已配置/活跃 collection 合并为前端展示 DTO。
     */
    static List<KnowledgeCollectionDto> buildCollectionDtos(
            List<KnowledgeRepositoryDtos.Item> repositoryItems,
            Set<String> collections) {
        Map<String, KnowledgeCollectionDto> collectionByName = new LinkedHashMap<>();
        for (KnowledgeRepositoryDtos.Item repository : repositoryItems) {
            List<String> repositoryCollections = repository.getCollections() == null ? List.of() : repository.getCollections();
            for (String collection : repositoryCollections) {
                if (StringUtils.isBlank(collection)) {
                    continue;
                }
                collectionByName.putIfAbsent(collection.trim(), toCollectionDto(repository, collection.trim()));
            }
        }
        for (String collection : collections) {
            collectionByName.putIfAbsent(collection, new KnowledgeCollectionDto()
                    .collection(collection)
                    .name(collection));
        }
        List<KnowledgeCollectionDto> sorted = new ArrayList<>(collectionByName.values());
        sorted.sort((left, right) -> StringUtils.defaultString(left.getCollection())
                .compareTo(StringUtils.defaultString(right.getCollection())));
        return sorted;
    }

    /**
     * 收集元数据配置与 hash-tree 活跃的 collection 名称。
     */
    private Set<String> collectConfiguredAndActiveCollections() {
        Set<String> collections = new LinkedHashSet<>();
        collections.addAll(metadataService.listConfiguredCollectionNames());
        collections.addAll(hashTreeService.loadActiveCollectionCounts().keySet());
        collections.removeIf(StringUtils::isBlank);
        Set<String> normalized = new LinkedHashSet<>();
        for (String collection : collections) {
            normalized.add(collection.trim());
        }
        return normalized;
    }

    /**
     * 将仓库条目转为 collection 展示 DTO。
     */
    private static KnowledgeCollectionDto toCollectionDto(KnowledgeRepositoryDtos.Item repository, String collection) {
        String alias = repository.getCollectionAliases() == null ? null : repository.getCollectionAliases().get(collection);
        String repositoryName = StringUtils.trimToNull(repository.getDisplayName());
        String name = StringUtils.firstNonBlank(alias, repositoryName, collection);
        return new KnowledgeCollectionDto()
                .collection(collection)
                .name(name)
                .repoCode(repository.getRepoCode())
                .repositoryName(repositoryName)
                .type(repository.getType());
    }

    /**
     * 按 collection 从 MySQL 查询逻辑文本块。
     */
    public KnowledgeGetListDto getKnowledge(Integer offset, Integer limit, String search, String collection, List<String> partitionNames) {
        KnowledgeGetListDto result = new KnowledgeGetListDto();
        if (StringUtils.isBlank(collection)) {
            result.setData(List.of());
            return result;
        }
        List<KnowledgeDto> data = knowledgeTextChunkService.listByCollection(
                        collection,
                        search,
                        offset == null ? 0 : offset,
                        limit == null ? 10 : limit
                ).stream()
                .map(this::toKnowledgeDto)
                .toList();
        result.setData(data);
        return result;
    }

    private KnowledgeDto toKnowledgeDto(KnowledgeTextChunkPo po) {
        KnowledgeDto knowledgeDto = new KnowledgeDto();
        knowledgeDto.setTextChunkId(po.getId());
        knowledgeDto.setOutline(StringUtils.isBlank(po.getHeadingPath()) ? List.of() : List.of(po.getHeadingPath()));
        knowledgeDto.setTextChunk(po.getTextChunk());
        knowledgeDto.setDimension(embeddingService.getDimension());
        knowledgeDto.setSourceFile(po.getSourceFile());
        return knowledgeDto;
    }
}
