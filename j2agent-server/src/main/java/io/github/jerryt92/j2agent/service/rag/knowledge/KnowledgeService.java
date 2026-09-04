package io.github.jerryt92.j2agent.service.rag.knowledge;

import io.github.jerryt92.j2agent.model.KnowledgeCollectionDto;
import io.github.jerryt92.j2agent.model.KnowledgeCollectionListDto;
import io.github.jerryt92.j2agent.model.KnowledgeDto;
import io.github.jerryt92.j2agent.model.KnowledgeGetListDto;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
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
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.jerryt92.j2agent.service.security.ResourceAccessService resourceAccess;
    /** 知识库展示名为空时的占位符 */
    private static final String EMPTY_REPOSITORY_NAME_PLACEHOLDER = "--";

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
     * 查询当前用户可读的知识库 collection（对话选库等）；不含管理端副作用。
     */
    public KnowledgeCollectionListDto getReadableKnowledgeCollections() {
        List<KnowledgeRepositoryPo> repositories = resourceAccess.readable(resourceAccess.current());
        List<KnowledgeRepositoryDtos.Item> repositoryItems = repositories.stream()
                .map(po -> {
                    KnowledgeRepositoryDtos.Item item = new KnowledgeRepositoryDtos.Item();
                    item.setId(po.getId());
                    item.setRepoCode(po.getRepoCode());
                    item.setDisplayName(po.getDisplayName());
                    String collection = io.github.jerryt92.j2agent.service.security.ResourceAccessService.collection(po);
                    item.setCollectionName(collection);
                    item.setCollections(StringUtils.isBlank(collection) ? List.of() : List.of(collection));
                    return item;
                })
                .toList();
        KnowledgeCollectionListDto result = new KnowledgeCollectionListDto();
        result.setData(repositoryItems.stream().map(repo -> {
            KnowledgeCollectionDto dto = new KnowledgeCollectionDto();
            dto.setCollection(repo.getCollectionName());
            dto.setSelectionValue(KnowledgeCollectionSelection.encode(repo.getRepoCode(), repo.getCollectionName()));
            dto.setName(StringUtils.defaultIfBlank(repo.getDisplayName(), repo.getRepoCode()));
            dto.setRepositoryId(repo.getId());
            return dto;
        }).filter(dto -> StringUtils.isNotBlank(dto.getCollection())).toList());
        return result;
    }

    /**
     * 查询管理端可用的知识库 collection 列表（含展示名等元信息）。
     */
    public KnowledgeCollectionListDto getKnowledgeCollections() {
        KnowledgeRepositoryDtos.ListResponse repositories = repositoryService.list();
        List<KnowledgeRepositoryDtos.Item> repositoryItems = repositories == null || repositories.getData() == null
                ? List.of()
                : repositories.getData();
        Set<String> collections = collectConfiguredAndActiveCollections();
        KnowledgeCollectionListDto result = new KnowledgeCollectionListDto();
        result.setData(repositoryItems.stream().map(repo -> {
            KnowledgeCollectionDto dto=new KnowledgeCollectionDto();
            dto.setCollection(repo.getCollectionName());
            dto.setSelectionValue(KnowledgeCollectionSelection.encode(repo.getRepoCode(),repo.getCollectionName()));
            dto.setName(StringUtils.defaultIfBlank(repo.getDisplayName(),repo.getRepoCode()));
            dto.setRepositoryId(repo.getId());
            return dto;
        }).toList());
        return result;
    }

    /**
     * 将仓库元数据与已配置/活跃 collection 合并为前端展示 DTO；同一 collection 去重并拼接展示名。
     */
    static List<KnowledgeCollectionDto> buildCollectionDtos(
            List<KnowledgeRepositoryDtos.Item> repositoryItems,
            Set<String> collections) {
        List<KnowledgeCollectionDto> result = new ArrayList<>();
        Map<String, CollectionOption> repositoryCollections = new LinkedHashMap<>();
        for (KnowledgeRepositoryDtos.Item repository : repositoryItems) {
            List<String> itemCollections = repository.getCollections() == null ? List.of() : repository.getCollections();
            for (String collection : itemCollections) {
                if (StringUtils.isBlank(collection)) {
                    continue;
                }
                String normalizedCollection = collection.trim();
                repositoryCollections.computeIfAbsent(normalizedCollection, CollectionOption::new)
                        .addRepository(repository);
            }
        }
        result.addAll(repositoryCollections.values().stream()
                .map(CollectionOption::toDto)
                .toList());
        List<String> fallbackCollections = collections.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .filter(collection -> !repositoryCollections.containsKey(collection))
                .sorted()
                .toList();
        for (String collection : fallbackCollections) {
            KnowledgeCollectionDto dto = new KnowledgeCollectionDto();
            dto.setCollection(collection);
            dto.setName(EMPTY_REPOSITORY_NAME_PLACEHOLDER);
            result.add(dto);
        }
        return result;
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
     * 同一 collection 下多仓库选项的聚合器。
     */
    private static class CollectionOption {
        private final String collection;
        private final LinkedHashSet<String> repositoryNames = new LinkedHashSet<>();

        private CollectionOption(String collection) {
            this.collection = collection;
        }

        private CollectionOption addRepository(KnowledgeRepositoryDtos.Item repository) {
            String repositoryName = StringUtils.defaultIfBlank(
                    StringUtils.trimToNull(repository.getDisplayName()),
                    StringUtils.defaultIfBlank(repository.getRepoCode(), EMPTY_REPOSITORY_NAME_PLACEHOLDER));
            repositoryNames.add(repositoryName);
            return this;
        }

        private KnowledgeCollectionDto toDto() {
            String name = String.join(", ", repositoryNames);
            KnowledgeCollectionDto dto = new KnowledgeCollectionDto();
            dto.setCollection(collection);
            dto.setName(name);
            return dto;
        }
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
        var selections=resourceAccess.resolveCollections(resourceAccess.current(),List.of(collection),true);
        var prefixes=selections.stream().map(s -> KnowledgeCollectionSelection.parse(s).repoCode()+"/").toList();
        try(var scope=new io.github.jerryt92.j2agent.service.security.KnowledgeReadScope(prefixes)) {
        List<KnowledgeDto> data = knowledgeTextChunkService.listByCollection(
                        KnowledgeCollectionSelection.parse(collection).collection(),
                        search,
                        offset == null ? 0 : offset,
                        limit == null ? 10 : limit
                ).stream()
                .map(this::toKnowledgeDto)
                .toList();
        result.setData(data);
        return result;
        }
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
