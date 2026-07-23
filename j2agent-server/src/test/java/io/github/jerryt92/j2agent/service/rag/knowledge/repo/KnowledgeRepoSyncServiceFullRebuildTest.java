package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.VectorDatabaseInit;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeTextChunkService;
import io.github.jerryt92.j2agent.service.rag.knowledge.MilvusKnowledgeWriteService;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRepoSyncServiceFullRebuildTest {

    @Mock
    private KnowledgeRepoMetadataService metadataService;
    @Mock
    private KnowledgeRepoHashTreeService hashTreeService;
    @Mock
    private KnowledgeTextChunkParser knowledgeTextChunkParser;
    @Mock
    private KnowledgeMarkdownImageRewriter knowledgeMarkdownImageRewriter;
    @Mock
    private MilvusKnowledgeWriteService milvusKnowledgeWriteService;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private VectorDatabaseService vectorDatabaseService;
    @Mock
    private VectorDatabaseInit vectorDatabaseInit;
    @Mock
    private KnowledgeTextChunkService knowledgeTextChunkService;

    @TempDir
    Path tempRepo;

    @Test
    void executeFullRebuild_dropsKnowledgeCollectionsBeforeProbe() {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(metadataService.listConfiguredCollectionNames()).thenReturn(Set.of("current_collection"));
        when(metadataService.hasMetadata()).thenReturn(false);
        when(hashTreeService.loadActiveCollectionCounts()).thenReturn(Map.of("old_count_collection", 1L));
        when(hashTreeService.loadActiveFileCollections()).thenReturn(Map.of("doc.md", "old_file_collection"));
        when(vectorDatabaseService.listCollections()).thenReturn(List.of(
                "current_collection", "old_count_collection", "old_file_collection", "foreign_collection"));
        when(vectorDatabaseInit.probeAndConfigure()).thenReturn(true);

        KnowledgeRepoSyncService syncService = new KnowledgeRepoSyncService(
                metadataService,
                hashTreeService,
                knowledgeTextChunkParser,
                knowledgeMarkdownImageRewriter,
                milvusKnowledgeWriteService,
                embeddingService,
                vectorDatabaseService,
                vectorDatabaseInit,
                knowledgeTextChunkService,
                new KnowledgeRepoSyncProgressTracker());

        assertTrue(syncService.executeFullRebuild(() -> true));

        InOrder order = inOrder(milvusKnowledgeWriteService, hashTreeService, knowledgeTextChunkService, vectorDatabaseService, vectorDatabaseInit);
        order.verify(milvusKnowledgeWriteService).dropCollection("current_collection");
        order.verify(milvusKnowledgeWriteService).dropCollection("old_count_collection");
        order.verify(milvusKnowledgeWriteService).dropCollection("old_file_collection");
        order.verify(hashTreeService).deleteAll();
        order.verify(knowledgeTextChunkService).deleteAll();
        order.verify(vectorDatabaseService).resetClient();
        order.verify(vectorDatabaseInit).probeAndConfigure();
        verify(milvusKnowledgeWriteService, never()).dropAllCollections();
    }

    /**
     * 全量重建时，文档含非法 UTF-8 字节应替换后继续入库。
     */
    @Test
    void executeFullRebuild_replacesMalformedUtf8BytesWhenReadingDocument() throws Exception {
        Path document = tempRepo.resolve("bad.md");
        Files.write(document, new byte[]{
                '#', ' ', 'T', 'i', 't', 'l', 'e', '\n',
                'b', 'a', 'd', ' ', (byte) 0xC3, '('
        });
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(metadataService.listConfiguredCollectionNames()).thenReturn(Set.of("knowledge_collection"));
        when(metadataService.hasMetadata()).thenReturn(true);
        when(metadataService.resolveInfoJsonHash(document)).thenReturn("info-hash");
        when(metadataService.resolveCollection(document)).thenReturn("knowledge_collection");
        when(metadataService.resolvePartitionNames(document)).thenReturn(List.of("_default"));
        when(metadataService.resolveMinHeadingLevel(document)).thenReturn(1);
        when(hashTreeService.loadActiveCollectionCounts()).thenReturn(Map.of());
        when(hashTreeService.loadActiveFileCollections()).thenReturn(Map.of());
        when(hashTreeService.loadSnapshot()).thenReturn(Map.of());
        when(vectorDatabaseService.listCollections()).thenReturn(List.of("knowledge_collection"));
        when(vectorDatabaseInit.probeAndConfigure()).thenReturn(true);
        List<KnowledgeTextChunkParser.TextChunk> chunks = List.of(
                new KnowledgeTextChunkParser.TextChunk("chunk-1", "Title", "bad", "bad.md", false));
        when(knowledgeTextChunkParser.parse(eq("bad.md"), anyString(), eq(1), eq(false), eq("bad"))).thenReturn(chunks);
        when(knowledgeMarkdownImageRewriter.rewriteChunks(eq("bad.md"), eq(chunks))).thenReturn(chunks);

        KnowledgeRepoSyncService syncService = new KnowledgeRepoSyncService(
                metadataService,
                hashTreeService,
                knowledgeTextChunkParser,
                knowledgeMarkdownImageRewriter,
                milvusKnowledgeWriteService,
                embeddingService,
                vectorDatabaseService,
                vectorDatabaseInit,
                knowledgeTextChunkService,
                new KnowledgeRepoSyncProgressTracker());

        assertTrue(syncService.executeFullRebuild(() -> true));
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeTextChunkParser).parse(eq("bad.md"), contentCaptor.capture(), eq(1), eq(false), eq("bad"));
        assertTrue(contentCaptor.getValue().contains("bad �("));
        verify(milvusKnowledgeWriteService).upsertTextChunks(
                eq(chunks), eq("bad.md"), anyString(), eq("knowledge_collection"), eq(List.of("_default")), any());
    }

    /**
     * 全量重建时，单个文档失败应跳过并继续处理其余文档。
     */
    @Test
    void executeFullRebuild_continuesWhenOneDocumentFails() throws Exception {
        Path badDocument = tempRepo.resolve("bad.md");
        Path goodDocument = tempRepo.resolve("good.md");
        Files.writeString(badDocument, "# Bad\nbroken");
        Files.writeString(goodDocument, "# Good\na healthy document with more text");
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(metadataService.listConfiguredCollectionNames()).thenReturn(Set.of("knowledge_collection"));
        when(metadataService.hasMetadata()).thenReturn(true);
        when(metadataService.resolveInfoJsonHash(badDocument)).thenReturn("info-hash");
        when(metadataService.resolveInfoJsonHash(goodDocument)).thenReturn("info-hash");
        when(metadataService.resolveCollection(badDocument)).thenReturn("knowledge_collection");
        when(metadataService.resolveCollection(goodDocument)).thenReturn("knowledge_collection");
        when(metadataService.resolvePartitionNames(badDocument)).thenReturn(List.of("_default"));
        when(metadataService.resolvePartitionNames(goodDocument)).thenReturn(List.of("_default"));
        when(metadataService.resolveMinHeadingLevel(badDocument)).thenReturn(1);
        when(metadataService.resolveMinHeadingLevel(goodDocument)).thenReturn(1);
        when(hashTreeService.loadActiveCollectionCounts()).thenReturn(Map.of());
        when(hashTreeService.loadActiveFileCollections()).thenReturn(Map.of());
        when(hashTreeService.loadSnapshot()).thenReturn(Map.of());
        when(vectorDatabaseService.listCollections()).thenReturn(List.of("knowledge_collection"));
        when(vectorDatabaseInit.probeAndConfigure()).thenReturn(true);
        doThrow(new IllegalStateException("parse failed"))
                .when(knowledgeTextChunkParser).parse(eq("bad.md"), anyString(), eq(1), eq(false), eq("bad"));
        List<KnowledgeTextChunkParser.TextChunk> goodChunks = List.of(
                new KnowledgeTextChunkParser.TextChunk("chunk-1", "Good", "a healthy document", "good.md", false));
        when(knowledgeTextChunkParser.parse(eq("good.md"), anyString(), eq(1), eq(false), eq("good"))).thenReturn(goodChunks);
        when(knowledgeMarkdownImageRewriter.rewriteChunks(eq("good.md"), eq(goodChunks))).thenReturn(goodChunks);

        KnowledgeRepoSyncService syncService = new KnowledgeRepoSyncService(
                metadataService,
                hashTreeService,
                knowledgeTextChunkParser,
                knowledgeMarkdownImageRewriter,
                milvusKnowledgeWriteService,
                embeddingService,
                vectorDatabaseService,
                vectorDatabaseInit,
                knowledgeTextChunkService,
                new KnowledgeRepoSyncProgressTracker());

        assertTrue(syncService.executeFullRebuild(() -> true));
        verify(milvusKnowledgeWriteService).upsertTextChunks(
                eq(goodChunks), eq("good.md"), anyString(), eq("knowledge_collection"), eq(List.of("_default")), any());
        verify(hashTreeService).upsertActive(
                eq(Path.of("good.md")), anyString(), eq("info-hash"), eq("knowledge_collection"), eq(List.of("_default")),
                eq(0), eq(Files.size(goodDocument)), anyLong());
        verify(hashTreeService, never()).upsertActive(
                eq(Path.of("bad.md")), anyString(), eq("info-hash"), eq("knowledge_collection"), eq(List.of("_default")),
                eq(0), eq(Files.size(badDocument)), anyLong());
    }
}
