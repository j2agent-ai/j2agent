package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.VectorDatabaseInit;
import io.github.jerryt92.j2agent.mapper.ext.KnowledgeRepoSyncMapper;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.*;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RepositoryIsolationTest {
    @TempDir Path root;
    /** 共享 collection 上同步 A 不得触碰 B 的数据，也不得 drop collection。 */
    @Test void syncingAInSharedCollectionNeverTouchesBOrDropsCollection() throws Exception {
        Files.createDirectories(root.resolve("a"));Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("a/doc.md"),"# A\nA content");Files.writeString(root.resolve("b/doc.md"),"# B\nB content");
        var metadata=mock(KnowledgeRepoMetadataService.class);var hashes=mock(KnowledgeRepoHashTreeService.class);
        var writer=mock(MilvusKnowledgeWriteService.class);var embedding=mock(EmbeddingService.class);var vectors=mock(VectorDatabaseService.class);
        var chunks=mock(KnowledgeTextChunkService.class);
        var repoSyncMapper=mock(KnowledgeRepoSyncMapper.class);
        var images=mock(KnowledgeMarkdownImageRewriter.class);
        when(metadata.getRepoRootPath()).thenReturn(root);when(metadata.hasMetadata()).thenReturn(true);
        when(metadata.listConfiguredScanPaths()).thenReturn(List.of(root.resolve("a"),root.resolve("b")));
        when(metadata.resolveCollection(any())).thenReturn("shared");when(metadata.resolveMetadataConfigHash(any())).thenReturn("config");
        when(metadata.resolveMinHeadingLevel(any())).thenReturn(1);when(metadata.resolvePartitionNames(any())).thenReturn(List.of());
        when(embedding.isReady()).thenReturn(true);
        when(hashes.loadSnapshot()).thenReturn(Map.of("a/old.md","stale","b/doc.md","unchanged"));
        when(hashes.loadActiveFileCollections()).thenReturn(Map.of("a/old.md","shared","b/doc.md","shared"));
        when(images.rewriteChunks(anyString(),anyList())).thenAnswer(i->i.getArgument(1));
        when(writer.upsertTextChunks(anyList(),eq("a/doc.md"),anyString(),eq("shared"),anyList(),any())).thenReturn(1);
        var sync=new KnowledgeRepoSyncService(metadata,hashes,new KnowledgeTextChunkParser(),images,writer,embedding,vectors,mock(VectorDatabaseInit.class),chunks,new KnowledgeRepoSyncProgressTracker());
        ReflectionTestUtils.setField(sync,"repoSyncMapper",repoSyncMapper);
        var repoProgress=spy(new KnowledgeRepositoryProgressTracker());
        ReflectionTestUtils.setField(sync,"repositoryProgressTracker",repoProgress);
        sync.executeRepositorySync("a",()->true);
        verify(writer).deleteBySourceFile("shared","a/old.md");
        verify(writer,never()).deleteBySourceFile(anyString(),startsWith("b/"));
        verify(writer,never()).dropCollection(anyString());verify(hashes,never()).deleteAll();verify(chunks,never()).deleteAll();
        verify(repoSyncMapper,never()).deleteFileHashesByRepoCode("a");
        verify(metadata,never()).resolveCollection(root.resolve("b/doc.md"));
        verify(hashes).upsertActive(eq(Path.of("a/doc.md")),anyString(),eq("config"),eq("shared"),anyList(),eq(1),anyLong(),anyLong());
        verify(repoProgress).begin("a",2);
        verify(repoProgress,times(2)).increment("a");
        verify(repoProgress).clear("a");
        verify(repoProgress,never()).begin(eq("b"),anyInt());
        verify(repoProgress,never()).increment("b");
    }

    /** 按库同步保留哈希，未变更文件跳过 embedding。 */
    @Test void repositorySyncSkipsUnchangedFilesByHash() throws Exception {
        Files.createDirectories(root.resolve("a"));
        Path doc=root.resolve("a/doc.md");
        Files.writeString(doc,"# A\nA content");
        String fileSha=io.github.jerryt92.j2agent.utils.HashUtil.getMessageDigest(
                Files.readAllBytes(doc), io.github.jerryt92.j2agent.utils.HashUtil.MdAlgorithm.SHA256);
        String diff=KnowledgeRepoDiffHash.build(fileSha,"config","shared");
        var metadata=mock(KnowledgeRepoMetadataService.class);var hashes=mock(KnowledgeRepoHashTreeService.class);
        var writer=mock(MilvusKnowledgeWriteService.class);var embedding=mock(EmbeddingService.class);var vectors=mock(VectorDatabaseService.class);
        var chunks=mock(KnowledgeTextChunkService.class);
        var repoSyncMapper=mock(KnowledgeRepoSyncMapper.class);
        var images=mock(KnowledgeMarkdownImageRewriter.class);
        when(metadata.getRepoRootPath()).thenReturn(root);when(metadata.hasMetadata()).thenReturn(true);
        when(metadata.listConfiguredScanPaths()).thenReturn(List.of(root.resolve("a")));
        when(metadata.resolveCollection(any())).thenReturn("shared");when(metadata.resolveMetadataConfigHash(any())).thenReturn("config");
        when(metadata.resolveMinHeadingLevel(any())).thenReturn(1);when(metadata.resolvePartitionNames(any())).thenReturn(List.of());
        when(embedding.isReady()).thenReturn(true);
        when(hashes.loadSnapshot()).thenReturn(Map.of("a/doc.md",diff));
        when(hashes.loadActiveFileCollections()).thenReturn(Map.of("a/doc.md","shared"));
        var sync=new KnowledgeRepoSyncService(metadata,hashes,new KnowledgeTextChunkParser(),images,writer,embedding,vectors,mock(VectorDatabaseInit.class),chunks,new KnowledgeRepoSyncProgressTracker());
        ReflectionTestUtils.setField(sync,"repoSyncMapper",repoSyncMapper);
        var repoProgress=spy(new KnowledgeRepositoryProgressTracker());
        ReflectionTestUtils.setField(sync,"repositoryProgressTracker",repoProgress);
        sync.executeRepositorySync("a",()->true);
        verify(writer,never()).upsertTextChunks(anyList(),anyString(),anyString(),anyString(),anyList(),any());
        verify(writer,never()).deleteBySourceFile(anyString(),anyString());
        verify(hashes,never()).upsertActive(any(),anyString(),anyString(),anyString(),anyList(),anyInt(),anyLong(),anyLong());
        verify(repoSyncMapper,never()).deleteFileHashesByRepoCode("a");
        verify(repoProgress).begin("a",0);
        verify(repoProgress,never()).increment("a");
        verify(repoProgress).clear("a");
    }

    @Test void invalidScopeCannotDeleteAnything() {
        var metadata=mock(KnowledgeRepoMetadataService.class);when(metadata.getRepoRootPath()).thenReturn(root);
        var writer=mock(MilvusKnowledgeWriteService.class);
        var repoSyncMapper=mock(KnowledgeRepoSyncMapper.class);
        var sync=new KnowledgeRepoSyncService(metadata,mock(KnowledgeRepoHashTreeService.class),new KnowledgeTextChunkParser(),mock(KnowledgeMarkdownImageRewriter.class),writer,mock(EmbeddingService.class),mock(VectorDatabaseService.class),mock(VectorDatabaseInit.class),mock(KnowledgeTextChunkService.class),new KnowledgeRepoSyncProgressTracker());
        ReflectionTestUtils.setField(sync,"repoSyncMapper",repoSyncMapper);
        assertThrows(IllegalArgumentException.class,()->sync.deleteRepositoryData("../b"));
        verifyNoInteractions(writer);
        verifyNoInteractions(repoSyncMapper);
    }
}
