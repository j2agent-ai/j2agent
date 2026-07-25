package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.service.rag.knowledge.repository.KnowledgeRepositoryAutoRegistrar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeRepoMetadataServiceCollectionNamesTest {
    @TempDir
    Path tempDir;

    @Test
    void listConfiguredCollectionNames_deduplicatesAndTrims() {
        KnowledgeRepositoryMapper mapper = mock(KnowledgeRepositoryMapper.class);
        when(mapper.selectEnabledAll()).thenReturn(List.of(
                repository("repo-a", " rc_wiki "),
                repository("repo-b", "rc_wiki"),
                repository("repo-c", "other")));
        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        properties.setRootPath(tempDir.toString());
        KnowledgeRepositoryAutoRegistrar autoRegistrar = new KnowledgeRepositoryAutoRegistrar(mapper, properties);
        KnowledgeRepoMetadataService service = new KnowledgeRepoMetadataService(properties, mapper, autoRegistrar);
        service.init();

        Set<String> names = service.listConfiguredCollectionNames();

        assertEquals(Set.of("rc_wiki", "other"), names);
    }

    private static KnowledgeRepositoryPo repository(String repoCode, String collection) {
        KnowledgeRepositoryPo po = new KnowledgeRepositoryPo();
        po.setRepoCode(repoCode);
        po.setEnabled(true);
        po.setMetadataConfig("{\"collectionName\":\"" + collection
                + "\",\"partitionNames\":[],\"minHeadingLevel\":3,\"filenameAsTitle\":true}");
        return po;
    }
}
