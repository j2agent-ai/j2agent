package io.github.jerryt92.j2agent.service.security;

import io.github.jerryt92.j2agent.mapper.ext.ResourcePermissionMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeCollectionSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * 校验普通用户通过助手选库时，repositoryId 与 selectionValue 均可解析。
 */
@ExtendWith(MockitoExtension.class)
class ResourceAccessServiceResolveCollectionsTest {
    @Mock
    private ResourcePermissionCache cache;
    @Mock
    private LoginService login;

    private ResourceAccessService access;
    private UserContextBo user;
    private KnowledgeRepositoryPo repository;

    @BeforeEach
    void setUp() {
        access = spy(new ResourceAccessService(cache, mock(ResourcePermissionMapper.class), login));
        user = new UserContextBo();
        user.setUserId("user-1");
        user.setRole(UserRoleEnum.USER);
        repository = new KnowledgeRepositoryPo();
        repository.setId("repo-id-1");
        repository.setRepoCode("docs_kb");
        repository.setStatus("SYNCED");
        repository.setMetadataConfig("{\"collectionName\":\"kb_docs\"}");
        doReturn(List.of(repository)).when(access).readable(user);
    }

    @Test
    void resolveCollections_acceptsRepositoryIdForAuthorizedUser() {
        List<String> resolved = access.resolveCollections(user, List.of("repo-id-1"), true);
        assertEquals(List.of(KnowledgeCollectionSelection.encode("docs_kb", "kb_docs")), resolved);
    }

    @Test
    void resolveCollections_acceptsSelectionValueForAuthorizedUser() {
        String selection = KnowledgeCollectionSelection.encode("docs_kb", "kb_docs");
        List<String> resolved = access.resolveCollections(user, List.of(selection), true);
        assertEquals(List.of(selection), resolved);
    }
}
