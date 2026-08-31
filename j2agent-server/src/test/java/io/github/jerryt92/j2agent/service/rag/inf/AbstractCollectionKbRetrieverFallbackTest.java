package io.github.jerryt92.j2agent.service.rag.inf;

import io.github.jerryt92.j2agent.service.rag.retrieval.Retriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbstractCollectionKbRetrieverFallbackTest {

    @Mock
    private Retriever retriever;

    @Test
    void retrieve_whenEmbeddingFailed_returnsFallbackDocument() {
        when(retriever.retrieveRagChunksResult("question", "docs", null, null))
                .thenReturn(new Retriever.RagChunksResult(
                        Collections.emptyList(),
                        Retriever.RetrievalStatus.FAILED,
                        "Embedding 服务连接失败"));

        AbstractCollectionKbRetriever kbRetriever = new AbstractCollectionKbRetriever(retriever) {
            @Override
            protected List<String> boundCollections() {
                return List.of("docs");
            }
        };

        List<Document> documents = kbRetriever.retrieve(Query.builder().text("question").build());

        assertEquals(1, documents.size());
        Document fallback = documents.getFirst();
        assertTrue(fallback.getText().contains("知识库向量检索失败"));
        assertEquals("rag-system", fallback.getMetadata().get("sourceFile"));
        assertEquals("milvus_retrieval_failed", fallback.getMetadata().get("ragFallback"));
    }
}
