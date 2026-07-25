package io.github.jerryt92.j2agent.service.rag.knowledge;

import io.github.jerryt92.j2agent.model.KnowledgeCollectionDto;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * KnowledgeService collection 列表合并与展示名逻辑测试。
 */
class KnowledgeServiceCollectionsTest {

    @Test
    void getKnowledgeCollections_mergesConfiguredAndActiveCollections() {
        KnowledgeRepositoryDtos.Item wiki = new KnowledgeRepositoryDtos.Item();
        wiki.setRepoCode("wiki");
        wiki.setType("REMOTE");
        wiki.setDisplayName("研发Wiki");
        wiki.setCollections(List.of("rc_wiki"));
        Set<String> collections = Set.of("rc_wiki", "inc_qa", "legacy_kb");
        List<KnowledgeCollectionDto> result = KnowledgeService.buildCollectionDtos(List.of(wiki), collections);

        assertEquals(List.of("rc_wiki", "inc_qa", "legacy_kb"), result.stream()
                .map(item -> item.getCollection())
                .toList());
        assertEquals("研发Wiki", result.getFirst().getName());
    }

    @Test
    void getKnowledgeCollections_groupsDuplicateCollectionsAndJoinsRepositoryNames() {
        KnowledgeRepositoryDtos.Item first = new KnowledgeRepositoryDtos.Item();
        first.setRepoCode("j2agent-docs");
        first.setType("REMOTE");
        first.setDisplayName("J2Agent 平台系统文档");
        first.setCollections(List.of("knowledge_base"));

        KnowledgeRepositoryDtos.Item second = new KnowledgeRepositoryDtos.Item();
        second.setRepoCode("custom-docs");
        second.setType("REMOTE");
        second.setDisplayName("自定义系统文档");
        second.setCollections(List.of("knowledge_base"));

        KnowledgeRepositoryDtos.Item local = new KnowledgeRepositoryDtos.Item();
        local.setRepoCode("knowledge_base");
        local.setType("LOCAL");
        local.setCollections(List.of("knowledge_base"));

        List<KnowledgeCollectionDto> result = KnowledgeService.buildCollectionDtos(
                List.of(first, local, second),
                Set.of("knowledge_base"));

        assertEquals(List.of("knowledge_base"), result.stream()
                .map(KnowledgeCollectionDto::getCollection)
                .toList());
        assertEquals(List.of("J2Agent 平台系统文档, knowledge_base, 自定义系统文档"), result.stream()
                .map(KnowledgeCollectionDto::getName)
                .toList());
    }

    @Test
    void getKnowledgeCollections_usesPlaceholderWhenRepositoryNamesAreBlank() {
        KnowledgeRepositoryDtos.Item local = new KnowledgeRepositoryDtos.Item();
        local.setRepoCode("knowledge_base");
        local.setType("LOCAL");
        local.setCollections(List.of("knowledge_base"));

        List<KnowledgeCollectionDto> result = KnowledgeService.buildCollectionDtos(
                List.of(local),
                Set.of("knowledge_base"));

        assertEquals(List.of("knowledge_base"), result.stream()
                .map(KnowledgeCollectionDto::getCollection)
                .toList());
        assertEquals(List.of("knowledge_base"), result.stream()
                .map(KnowledgeCollectionDto::getName)
                .toList());
    }
}
