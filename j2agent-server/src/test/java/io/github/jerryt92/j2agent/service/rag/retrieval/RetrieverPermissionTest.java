package io.github.jerryt92.j2agent.service.rag.retrieval;

import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.model.po.KnowledgeTextChunkPo;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.service.PropertiesService;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.inf.AbstractCollectionKbRetriever;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeCollectionSelection;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeTextChunkService;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import io.github.jerryt92.j2agent.service.security.AgentAccessContext;
import io.github.jerryt92.j2agent.service.security.ResourceAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetrieverPermissionTest {
    private final EmbeddingService embeddings = mock(EmbeddingService.class);
    private final VectorDatabaseService vectors = mock(VectorDatabaseService.class);
    private final KnowledgeTextChunkService chunks = mock(KnowledgeTextChunkService.class);
    private final ResourceAccessService access = mock(ResourceAccessService.class);
    private final PropertiesService properties = mock(PropertiesService.class);
    private final QueryChunker queryChunker = mock(QueryChunker.class);
    private final Retriever retriever = new Retriever(embeddings, vectors, properties, queryChunker, chunks, access);
    private final UserContextBo user = new UserContextBo();

    RetrieverPermissionTest() {
        user.setUserId("ordinary-user");
        user.setRole(UserRoleEnum.USER);
        stubRetrieverParams();
    }

    @AfterEach
    void cleanup() {
        AgentAccessContext.clear("permission-test");
    }

    @Test
    void agentAccessAloneDoesNotStartKnowledgeRetrieval() {
        AgentAccessContext.bind("permission-test", user);
        when(access.resolveCollections(user, List.of("private_docs"), false)).thenReturn(List.of());
        var result = retriever.retrieveRagChunksResult("question", "private_docs", null, "permission-test");
        assertEquals(Retriever.RetrievalStatus.SKIPPED_NO_ACCESS, result.status());
        assertTrue(result.items().isEmpty());
        verifyNoInteractions(embeddings, vectors, chunks);
        verify(access, org.mockito.Mockito.never()).current();
    }

    @Test
    void unavailableRunIdentityCannotFallBackToAdminThreadSession() {
        var error = assertThrows(ResponseStatusException.class,
                () -> retriever.retrieveRagChunksResult("question", "private_docs", null, "permission-test"));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        verifyNoInteractions(embeddings, vectors, chunks);
    }

    @Test
    void explicitRepositoryLossOfAccessIsSkippedInConversation() {
        AgentAccessContext.bind("permission-test", user);
        String selection = KnowledgeCollectionSelection.encode("private-repo", "shared_docs");
        when(access.resolveCollections(user, List.of(selection), true))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "KNOWLEDGE_ACCESS_DENIED"));
        var result = retriever.retrieveRagChunksResult("question", selection, null, "permission-test");
        assertEquals(Retriever.RetrievalStatus.SKIPPED_NO_ACCESS, result.status());
        assertTrue(result.items().isEmpty());
        verifyNoInteractions(embeddings, vectors, chunks);
    }

    @Test
    void explicitRepositoryLossOfAccessOnHttpApiStillForbidden() {
        when(access.current()).thenReturn(user);
        String selection = KnowledgeCollectionSelection.encode("private-repo", "shared_docs");
        when(access.resolveCollections(user, List.of(selection), true))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "KNOWLEDGE_ACCESS_DENIED"));
        var error = assertThrows(ResponseStatusException.class,
                () -> retriever.retrieveKnowledge("question", 5, selection));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(embeddings, vectors, chunks);
    }

    @Test
    void boundCollectionWithNoAccessProducesNeitherDocumentsNorFallback() {
        when(access.current()).thenReturn(user);
        when(access.resolveCollections(user, List.of("private_docs"), false)).thenReturn(List.of());
        var bound = new AbstractCollectionKbRetriever(retriever) {
            @Override
            protected List<String> boundCollections() {
                return List.of("private_docs");
            }
        };
        assertTrue(bound.retrieve(Query.builder().text("question").build()).isEmpty());
        verifyNoInteractions(embeddings, vectors, chunks);
    }

    @Test
    void hydrateDoesNotReturnPrivateRepositoryBody() {
        AgentAccessContext.bind("permission-test", user);
        String allowed = KnowledgeCollectionSelection.encode("public-repo", "shared_docs");
        when(access.resolveCollections(user, List.of("shared_docs"), false)).thenReturn(List.of(allowed));
        when(embeddings.isReady()).thenReturn(true);
        when(queryChunker.chunk("question")).thenReturn(List.of("question"));
        when(embeddings.embed(any())).thenReturn(new EmbeddingModel.EmbeddingsResponse()
                .setData(List.of(new EmbeddingModel.EmbeddingsItem().setEmbeddings(new float[]{0.1f, 0.2f}))));
        EmbeddingModel.EmbeddingsQueryItem milvusHit = new EmbeddingModel.EmbeddingsQueryItem()
                .setTextChunkId("private-chunk")
                .setSourceFile("public-repo/ok.md")
                .setText("milvus-window")
                .setDenseScore(0.9f)
                .setSparseScore(0.8f);
        when(vectors.hybridRetrieval(eq("shared_docs"), any(), any(), anyInt(), any(), anyFloat(), anyFloat(), isNull()))
                .thenReturn(List.of(milvusHit));
        KnowledgeTextChunkPo privateChunk = new KnowledgeTextChunkPo();
        privateChunk.setId("private-chunk");
        privateChunk.setTextChunk("SECRET PRIVATE BODY");
        privateChunk.setSourceFile("private-repo/secret.md");
        when(chunks.getByIds(anyList())).thenReturn(Map.of("private-chunk", privateChunk));

        var result = retriever.retrieveRagChunksResult("question", "shared_docs", null, "permission-test");

        assertEquals(Retriever.RetrievalStatus.EMPTY, result.status());
        assertTrue(result.items().isEmpty());
        assertTrue(result.items().stream().noneMatch(item ->
                "SECRET PRIVATE BODY".equals(item.getTextChunk())
                        || "SECRET PRIVATE BODY".equals(item.getText())));
    }

    private void stubRetrieverParams() {
        when(properties.getProperty(PropertiesService.RETRIEVE_TOP_K)).thenReturn("5");
        when(properties.getProperty(PropertiesService.RETRIEVE_METRIC_TYPE)).thenReturn("COSINE");
        when(properties.getProperty(PropertiesService.RETRIEVE_METRIC_SCORE_COMPARE_EXPR)).thenReturn(">0");
        when(properties.getProperty(PropertiesService.RETRIEVE_DENSE_WEIGHT)).thenReturn("0.5");
        when(properties.getProperty(PropertiesService.RETRIEVE_SPARSE_WEIGHT)).thenReturn("0.5");
    }
}
