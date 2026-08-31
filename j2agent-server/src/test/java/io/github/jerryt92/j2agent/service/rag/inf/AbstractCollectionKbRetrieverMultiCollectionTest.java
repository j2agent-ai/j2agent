package io.github.jerryt92.j2agent.service.rag.inf;

import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.service.rag.retrieval.Retriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbstractCollectionKbRetrieverMultiCollectionTest {

    @Mock
    private Retriever retriever;

    @Test
    void retrieve_queriesEveryBoundCollectionAndDeduplicatesDocuments() {
        when(retriever.retrieveRagChunksResult("question", "first", null, null))
                .thenReturn(success(chunk("shared", "first.md", "shared text")));
        when(retriever.retrieveRagChunksResult("question", "second", null, null))
                .thenReturn(success(
                        chunk("shared", "second.md", "duplicate text"),
                        chunk("second-only", "second.md", "second text")));

        AbstractCollectionKbRetriever kbRetriever = new AbstractCollectionKbRetriever(retriever) {
            @Override
            protected List<String> boundCollections() {
                return List.of("first", "second", "first");
            }
        };

        List<Document> documents = kbRetriever.retrieve(Query.builder().text("question").build());

        assertEquals(2, documents.size());
        assertEquals("shared", documents.getFirst().getMetadata().get("textChunkId"));
        assertEquals("second-only", documents.get(1).getMetadata().get("textChunkId"));
        verify(retriever).retrieveRagChunksResult("question", "first", null, null);
        verify(retriever).retrieveRagChunksResult("question", "second", null, null);
    }

    private static Retriever.RagChunksResult success(EmbeddingModel.EmbeddingsQueryItem... items) {
        return new Retriever.RagChunksResult(List.of(items), Retriever.RetrievalStatus.SUCCESS, null);
    }

    private static EmbeddingModel.EmbeddingsQueryItem chunk(String id, String sourceFile, String textChunk) {
        return new EmbeddingModel.EmbeddingsQueryItem()
                .setTextChunkId(id)
                .setSourceFile(sourceFile)
                .setTextChunk(textChunk)
                .setScore(0.9f);
    }
}
