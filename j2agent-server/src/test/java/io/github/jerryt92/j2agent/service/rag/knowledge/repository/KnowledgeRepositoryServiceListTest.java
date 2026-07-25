package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMaintenanceCoordinator;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoSyncOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KnowledgeRepositoryServiceListTest {
    @TempDir
    Path tempDir;

    @Test
    void listUsesConfiguredRepositoriesOnly() {
        KnowledgeRepositoryPo localConfig = configured(
                "local-id",
                "local_kb",
                KnowledgeRepositoryConstants.TYPE_LOCAL_FILE,
                null,
                "local_collection");
        localConfig.setMetadataConfig(metadataConfig("local_collection", "[]", 2, true));

        KnowledgeRepositoryPo remoteConfig = configured(
                "remote-id",
                "remote_kb",
                KnowledgeRepositoryConstants.TYPE_REMOTE,
                "https://example.com/repo.git",
                "remote_collection");
        remoteConfig.setDefaultBranch("main");
        remoteConfig.setUpdateIntervalMinutes(60);
        remoteConfig.setMetadataConfig(metadataConfig("remote_collection", "[\"_default\"]", 3, true));

        KnowledgeRepositoryService service = service(new FakeKnowledgeRepositoryMapper(List.of(localConfig, remoteConfig)));

        List<KnowledgeRepositoryDtos.Item> items = service.list().getData();

        assertEquals(2, items.size());
        KnowledgeRepositoryDtos.Item localItem = items.get(0);
        KnowledgeRepositoryDtos.Item remoteItem = items.get(1);
        assertEquals("local_kb", localItem.getRepoCode());
        assertEquals("LOCAL_FILE", localItem.getType());
        assertEquals(List.of("local_collection"), localItem.getCollections());
        assertEquals("local_collection", localItem.getCollectionName());
        assertEquals(2, localItem.getMinHeadingLevel());
        assertFalse(localItem.getReadonly());
        assertEquals("remote_kb", remoteItem.getRepoCode());
        assertEquals("REMOTE", remoteItem.getType());
        assertEquals(List.of("remote_collection"), remoteItem.getCollections());
        assertEquals(List.of("_default"), remoteItem.getPartitionNames());
        assertEquals("GIT", remoteItem.getProtocol());
        assertEquals("https://example.com/repo.git", remoteItem.getRemoteUrl());
    }

    @Test
    void listShowsDirectoryMissingForConfiguredPathWithoutDirectory() {
        KnowledgeRepositoryPo missingConfig = configured(
                "missing-id",
                "missing_remote",
                KnowledgeRepositoryConstants.TYPE_REMOTE,
                "https://example.com/missing.git",
                "kb_missing_remote");
        KnowledgeRepositoryService service = service(new FakeKnowledgeRepositoryMapper(List.of(missingConfig)));

        KnowledgeRepositoryDtos.Item item = service.list().getData().getFirst();

        assertEquals("missing-id", item.getId());
        assertEquals("missing_remote", item.getRepoCode());
        assertEquals("DIRECTORY_MISSING", item.getStatus());
        assertEquals(List.of("kb_missing_remote"), item.getCollections());
        assertEquals("https://example.com/missing.git", item.getRemoteUrl());
        assertFalse(item.getReadonly());
        assertEquals("kb_missing_remote", item.getCollectionName());
    }

    @Test
    void listAutoCreatesLocalFileRepositoryForExistingTopLevelDirectory() throws IOException {
        Path rootPath = tempDir.resolve("knowledge-repo");
        Files.createDirectories(rootPath.resolve("local_docs"));
        FakeKnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper(new ArrayList<>());
        KnowledgeRepositoryService service = service(mapper);

        KnowledgeRepositoryDtos.Item item = service.list().getData().getFirst();

        assertEquals("local_docs", item.getRepoCode());
        assertEquals(KnowledgeRepositoryConstants.TYPE_LOCAL_FILE, item.getType());
        assertEquals(List.of("kb_local_docs"), item.getCollections());
        assertEquals(List.of(), item.getPartitionNames());
        assertEquals(3, item.getMinHeadingLevel());
        assertEquals(true, item.getFilenameAsTitle());
        assertEquals(1, mapper.rows.size());
    }

    @Test
    void deleteLocalFileRepositoryDeletesDirectoryAndConfig() throws IOException {
        Path rootPath = tempDir.resolve("knowledge-repo");
        Files.createDirectories(rootPath.resolve("local_docs"));
        Files.writeString(rootPath.resolve("local_docs").resolve("guide.md"), "# Guide\n");
        KnowledgeRepositoryPo localConfig = configured(
                "local-id",
                "local_docs",
                KnowledgeRepositoryConstants.TYPE_LOCAL_FILE,
                null,
                "kb_local_docs");
        FakeKnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper(new ArrayList<>(List.of(localConfig)));
        KnowledgeRepositoryService service = service(mapper);

        service.delete("local-id");

        assertFalse(Files.exists(rootPath.resolve("local_docs")));
        assertEquals(0, mapper.rows.size());
    }

    private KnowledgeRepositoryService service(FakeKnowledgeRepositoryMapper mapper) {
        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        properties.setRootPath(tempDir.resolve("knowledge-repo").toString());
        KnowledgeRepositoryCredentialCipher cipher = new KnowledgeRepositoryCredentialCipher(properties);
        KnowledgeRepositoryAutoRegistrar autoRegistrar = new KnowledgeRepositoryAutoRegistrar(mapper, properties);
        return new KnowledgeRepositoryService(mapper, properties, new FakeKnowledgeRepoMaintenanceCoordinator(), cipher, autoRegistrar, List.of());
    }

    private KnowledgeRepositoryPo configured(String id, String repoCode, String type, String remoteUrl, String collection) {
        KnowledgeRepositoryPo po = new KnowledgeRepositoryPo();
        po.setId(id);
        po.setRepoCode(repoCode);
        po.setType(type);
        po.setProtocol(KnowledgeRepositoryConstants.TYPE_REMOTE.equals(type) ? "GIT" : null);
        po.setEnabled(true);
        po.setStatus("SYNCED");
        po.setRemoteUrl(remoteUrl);
        po.setUpdateIntervalMinutes(60);
        po.setProtocolConfig("{}");
        po.setMetadataConfig(metadataConfig(collection, "[]", 3, true));
        return po;
    }

    private String metadataConfig(String collection, String partitionsJson, int minHeadingLevel, boolean filenameAsTitle) {
        return "{\"collectionName\":\"" + collection + "\",\"partitionNames\":" + partitionsJson
                + ",\"minHeadingLevel\":" + minHeadingLevel + ",\"filenameAsTitle\":" + filenameAsTitle + "}";
    }

    private static class FakeKnowledgeRepositoryMapper implements KnowledgeRepositoryMapper {
        private final List<KnowledgeRepositoryPo> rows;

        private FakeKnowledgeRepositoryMapper(List<KnowledgeRepositoryPo> rows) {
            this.rows = rows;
        }

        @Override
        public List<KnowledgeRepositoryPo> selectAll() {
            return rows;
        }

        @Override
        public List<KnowledgeRepositoryPo> selectRemoteAll() {
            return rows.stream()
                    .filter(row -> KnowledgeRepositoryConstants.TYPE_REMOTE.equals(row.getType()))
                    .toList();
        }

        @Override
        public List<KnowledgeRepositoryPo> selectEnabledAll() {
            return rows.stream()
                    .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                    .toList();
        }

        @Override
        public KnowledgeRepositoryPo selectById(String id) {
            return rows.stream().filter(row -> id.equals(row.getId())).findFirst().orElse(null);
        }

        @Override
        public KnowledgeRepositoryPo selectByRepoCode(String repoCode) {
            return rows.stream().filter(row -> repoCode.equals(row.getRepoCode())).findFirst().orElse(null);
        }

        @Override
        public List<KnowledgeRepositoryPo> selectDueRemote(long dueBefore) {
            return selectRemoteAll();
        }

        @Override
        public int insert(KnowledgeRepositoryPo po) {
            rows.add(po);
            return 1;
        }

        @Override
        public int updateConfig(KnowledgeRepositoryPo po) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateStatus(String id, String status, String lastError, long updatedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateSyncResult(KnowledgeRepositoryPo po) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteById(String id) {
            return rows.removeIf(row -> id.equals(row.getId())) ? 1 : 0;
        }
    }

    private static class FakeKnowledgeRepoMaintenanceCoordinator extends KnowledgeRepoMaintenanceCoordinator {
        private FakeKnowledgeRepoMaintenanceCoordinator() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public KnowledgeRepoSyncOutcome syncNowAsync(boolean fullRebuild) {
            return KnowledgeRepoSyncOutcome.accepted("queued");
        }
    }
}
