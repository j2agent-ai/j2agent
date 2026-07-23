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

        assertEquals(List.of("inc_qa", "legacy_kb", "rc_wiki"), result.stream()
                .map(item -> item.getCollection())
                .toList());
        assertEquals("研发Wiki", result.get(2).getName());
        assertEquals("wiki", result.get(2).getRepoCode());
    }
}
