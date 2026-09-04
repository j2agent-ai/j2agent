package io.github.jerryt92.j2agent.service.rag.retrieval;

import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.service.PropertiesService;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeCollectionSelection;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeTextChunkService;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import io.github.jerryt92.j2agent.service.security.ResourceAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrieverEmbeddingFailureTest {

    private static final String COLLECTION = "test-collection";
    private static final String PROBE_ERROR = "Embedding 服务连接失败: Connection refused";

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private VectorDatabaseService vectorDatabaseService;
    @Mock
    private PropertiesService propertiesService;
    @Mock
    private QueryChunker queryChunker;
    @Mock
    private KnowledgeTextChunkService knowledgeTextChunkService;
    @Mock
    private ResourceAccessService resourceAccess;

    private Retriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new Retriever(
                embeddingService,
                vectorDatabaseService,
                propertiesService,
                queryChunker,
                knowledgeTextChunkService,
                resourceAccess);
        stubRetrieverParams();
        UserContextBo user = new UserContextBo();
        user.setUserId("tester");
        user.setRole(UserRoleEnum.USER);
        when(resourceAccess.current()).thenReturn(user);
        when(resourceAccess.resolveCollections(eq(user), eq(List.of(COLLECTION)), eq(false)))
                .thenReturn(List.of(KnowledgeCollectionSelection.encode("test-repo", COLLECTION)));
    }

    @Test
    void retrieveRagChunksResult_whenEmbeddingNotReady_returnsFailedWithoutHttp() {
        when(embeddingService.isReady()).thenReturn(false);
        when(embeddingService.getLastProbeError()).thenReturn(PROBE_ERROR);

        Retriever.RagChunksResult result = retriever.retrieveRagChunksResult("hello", COLLECTION, null);

        assertEquals(Retriever.RetrievalStatus.FAILED, result.status());
        assertEquals(PROBE_ERROR, result.failureMessage());
        assertTrue(result.items().isEmpty());
        verify(embeddingService, never()).embed(any());
        verify(vectorDatabaseService, never()).hybridRetrieval(any(), any(), any(), anyInt(), any(), anyFloat(), anyFloat(), any());
    }

    @Test
    void retrieveRagChunksResult_whenEmbedThrows_returnsFailed() {
        when(embeddingService.isReady()).thenReturn(true);
        when(queryChunker.chunk("hello")).thenReturn(List.of("hello"));
        when(embeddingService.embed(any())).thenThrow(new IllegalStateException("Connection refused"));

        Retriever.RagChunksResult result = retriever.retrieveRagChunksResult("hello", COLLECTION, null);

        assertEquals(Retriever.RetrievalStatus.FAILED, result.status());
        assertTrue(result.failureMessage().contains("Connection refused"));
        assertTrue(result.items().isEmpty());
    }

    private void stubRetrieverParams() {
        when(propertiesService.getProperty(PropertiesService.RETRIEVE_TOP_K)).thenReturn("5");
        when(propertiesService.getProperty(PropertiesService.RETRIEVE_METRIC_TYPE)).thenReturn("COSINE");
        when(propertiesService.getProperty(PropertiesService.RETRIEVE_METRIC_SCORE_COMPARE_EXPR)).thenReturn(">0");
        when(propertiesService.getProperty(PropertiesService.RETRIEVE_DENSE_WEIGHT)).thenReturn("0.5");
        when(propertiesService.getProperty(PropertiesService.RETRIEVE_SPARSE_WEIGHT)).thenReturn("0.5");
    }
}
