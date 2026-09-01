package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.VectorDatabaseInit;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeTextChunkService;
import io.github.jerryt92.j2agent.service.rag.knowledge.MilvusKnowledgeWriteService;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验入库被打断后残留数据可被识别与清理，避免重复入库或永久残缺。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeRepoSyncServiceInterruptionRecoveryTest {

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

    private KnowledgeRepoSyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new KnowledgeRepoSyncService(
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
        lenient().when(embeddingService.isReady()).thenReturn(true);
        lenient().when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
    }

    /**
     * 上一轮在某文件上被打断：该文件已写入部分向量却没有 ACTIVE 哈希，重新入库前必须先删残留，否则产生重复向量。
     */
    @Test
    void executeIncrementalSync_deletesResidueOfInterruptedFileBeforeReingest() throws Exception {
        Path document = tempRepo.resolve("doc.md");
        Files.writeString(document, "# Title\nbody text");
        when(metadataService.hasMetadata()).thenReturn(true);
        when(metadataService.listConfiguredScanPaths()).thenReturn(List.of(tempRepo));
        when(metadataService.resolveMetadataConfigHash(document)).thenReturn("metadata-hash");
        when(metadataService.resolveCollection(document)).thenReturn("kb");
        when(metadataService.resolvePartitionNames(document)).thenReturn(List.of());
        when(metadataService.resolveMinHeadingLevel(document)).thenReturn(1);
        when(hashTreeService.loadSnapshot()).thenReturn(Map.of("other.md", "hash"));
        when(hashTreeService.loadInFlightFileCollections()).thenReturn(Map.of("doc.md", "kb"));
        when(milvusKnowledgeWriteService.hasCollection("kb")).thenReturn(true);
        List<KnowledgeTextChunkParser.TextChunk> chunks = List.of(
                new KnowledgeTextChunkParser.TextChunk("chunk-1", "Title", "body text", "doc.md", false));
        when(knowledgeTextChunkParser.parse(eq("doc.md"), anyString(), eq(1), eq(false), eq("doc"))).thenReturn(chunks);
        when(knowledgeMarkdownImageRewriter.rewriteChunks(eq("doc.md"), eq(chunks))).thenReturn(chunks);

        syncService.executeIncrementalSync(() -> true);

        InOrder order = inOrder(milvusKnowledgeWriteService, hashTreeService);
        order.verify(milvusKnowledgeWriteService).deleteBySourceFile("kb", "doc.md");
        order.verify(hashTreeService).markSyncing(eq(Path.of("doc.md")), anyString(), eq("metadata-hash"), eq("kb"), anyList(), anyLong());
        order.verify(milvusKnowledgeWriteService).upsertTextChunks(
                eq(chunks), eq("doc.md"), anyString(), eq("kb"), anyList(), any());
        order.verify(hashTreeService).upsertActive(
                eq(Path.of("doc.md")), anyString(), eq("metadata-hash"), eq("kb"), anyList(), anyInt(), anyLong(), anyLong());
    }

    /**
     * 上一轮完全重建清空哈希后被打断：collection 里还是上一代全量数据，必须先 drop 再全量重入。
     */
    @Test
    void executeIncrementalSync_dropsOrphanCollectionWhenHashesCleared() {
        when(metadataService.hasMetadata()).thenReturn(false);
        when(metadataService.listConfiguredCollectionNames()).thenReturn(Set.of("kb"));
        when(hashTreeService.loadSnapshot()).thenReturn(Map.of());
        when(milvusKnowledgeWriteService.hasCollection("kb")).thenReturn(true);

        syncService.executeIncrementalSync(() -> true);

        verify(milvusKnowledgeWriteService).dropCollection("kb");
    }

    /**
     * 哈希非空说明上一轮重建已完成，不得误删现有 collection。
     */
    @Test
    void executeIncrementalSync_keepsCollectionWhenHashesPresent() {
        when(metadataService.hasMetadata()).thenReturn(false);
        when(hashTreeService.loadSnapshot()).thenReturn(Map.of("doc.md", "hash"));
        when(hashTreeService.loadActiveFileCollections()).thenReturn(Map.of("doc.md", "kb"));

        syncService.executeIncrementalSync(() -> true);

        verify(metadataService, never()).listConfiguredCollectionNames();
        verify(milvusKnowledgeWriteService, never()).dropCollection(anyString());
    }
}
