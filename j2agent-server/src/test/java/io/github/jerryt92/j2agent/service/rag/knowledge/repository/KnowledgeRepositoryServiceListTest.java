package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMaintenanceCoordinator;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoSyncOutcome;
import io.github.jerryt92.j2agent.service.security.ResourceAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void deleteSucceedsWhileRepositoryIsSyncing() throws IOException {
        Path rootPath = tempDir.resolve("knowledge-repo");
        Files.createDirectories(rootPath.resolve("remote_docs"));
        KnowledgeRepositoryPo remoteConfig = configured(
                "remote-id",
                "remote_docs",
                KnowledgeRepositoryConstants.TYPE_REMOTE,
                "https://example.com/docs.git",
                "kb_remote_docs");
        remoteConfig.setStatus(KnowledgeRepositoryConstants.STATUS_SYNCING);
        FakeKnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper(new ArrayList<>(List.of(remoteConfig)));
        KnowledgeRepositoryService service = service(mapper);

        service.delete("remote-id");

        verify((RepositoryMaintenanceService) ReflectionTestUtils.getField(service, "repositoryMaintenance"))
                .interruptRunning("remote-id");
        assertFalse(Files.exists(rootPath.resolve("remote_docs")));
        assertEquals(0, mapper.rows.size());
    }

    @Test
    void toItemShowsRebuildingStatus() throws IOException {
        Path rootPath = tempDir.resolve("knowledge-repo");
        Files.createDirectories(rootPath.resolve("local_kb"));
        KnowledgeRepositoryPo po = configured(
                "local-id",
                "local_kb",
                KnowledgeRepositoryConstants.TYPE_LOCAL_FILE,
                null,
                "local_collection");
        po.setStatus("REBUILDING");
        KnowledgeRepositoryService service = service(new FakeKnowledgeRepositoryMapper(List.of(po)));

        KnowledgeRepositoryDtos.Item item = service.list().getData().stream()
                .filter(row -> "local_kb".equals(row.getRepoCode()))
                .findFirst()
                .orElseThrow();

        assertEquals("REBUILDING", item.getStatus());

        po.setStatus("SYNCED");
        KnowledgeRepositoryDtos.Item idle = service.list().getData().stream()
                .filter(row -> "local_kb".equals(row.getRepoCode()))
                .findFirst()
                .orElseThrow();
        assertEquals("SYNCED", idle.getStatus());
    }

    @Test
    void listShowsGlobalRebuildingWhenExclusiveRebuildRuns() throws IOException {
        Path rootPath = tempDir.resolve("knowledge-repo");
        Files.createDirectories(rootPath.resolve("local_kb"));
        KnowledgeRepositoryPo po = configured(
                "local-id",
                "local_kb",
                KnowledgeRepositoryConstants.TYPE_LOCAL_FILE,
                null,
                "local_collection");
        po.setStatus("SYNCED");
        KnowledgeRepositoryService service = service(
                new FakeKnowledgeRepositoryMapper(List.of(po)),
                new FakeKnowledgeRepoMaintenanceCoordinator(true));

        KnowledgeRepositoryDtos.ListResponse response = service.list();
        KnowledgeRepositoryDtos.Item item = response.getData().stream()
                .filter(row -> "local_kb".equals(row.getRepoCode()))
                .findFirst()
                .orElseThrow();

        assertEquals(KnowledgeRepositoryConstants.STATUS_GLOBAL_REBUILDING, item.getStatus());
    }

    @Test
    void listShowsGlobalRebuildingForNonAdminWithRepoAccess() throws IOException {
        Path rootPath = tempDir.resolve("knowledge-repo");
        Files.createDirectories(rootPath.resolve("local_kb"));
        KnowledgeRepositoryPo po = configured(
                "local-id",
                "local_kb",
                KnowledgeRepositoryConstants.TYPE_LOCAL_FILE,
                null,
                "local_collection");
        po.setStatus("SYNCED");
        KnowledgeRepositoryService service = service(
                new FakeKnowledgeRepositoryMapper(List.of(po)),
                new FakeKnowledgeRepoMaintenanceCoordinator(true));
        UserContextBo user = new UserContextBo();
        user.setUserId("user-1");
        user.setRole(UserRoleEnum.USER);
        ResourceAccessService resourceAccess = mock(ResourceAccessService.class);
        when(resourceAccess.current()).thenReturn(user);
        when(resourceAccess.readable(user)).thenReturn(List.of(po));
        when(resourceAccess.repositories(eq(user), eq(true))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "resourceAccess", resourceAccess);

        KnowledgeRepositoryDtos.Item item = service.list().getData().getFirst();

        assertEquals(KnowledgeRepositoryConstants.STATUS_GLOBAL_REBUILDING, item.getStatus());
    }

    @Test
    void syncDueRepositoriesSkipsBusyRepoAndSwallowsConflict() {
        KnowledgeRepositoryPo rebuilding = configured(
                "busy-id",
                "busy_kb",
                KnowledgeRepositoryConstants.TYPE_REMOTE,
                "https://example.com/busy.git",
                "kb_busy");
        rebuilding.setStatus("REBUILDING");
        rebuilding.setLastSyncTime(null);
        KnowledgeRepositoryPo idle = configured(
                "idle-id",
                "idle_kb",
                KnowledgeRepositoryConstants.TYPE_REMOTE,
                "https://example.com/idle.git",
                "kb_idle");
        idle.setStatus("IDLE");
        idle.setLastSyncTime(null);
        KnowledgeRepositoryPo raced = configured(
                "race-id",
                "race_kb",
                KnowledgeRepositoryConstants.TYPE_REMOTE,
                "https://example.com/race.git",
                "kb_race");
        raced.setStatus("IDLE");
        raced.setLastSyncTime(null);
        KnowledgeRepositoryService service = service(new FakeKnowledgeRepositoryMapper(
                new ArrayList<>(List.of(rebuilding, idle, raced))));
        RepositoryMaintenanceService repoMaintenance = mock(RepositoryMaintenanceService.class);
        when(repoMaintenance.submit(any(), nullable(String.class), any(Runnable.class))).thenAnswer(inv -> {
            KnowledgeRepositoryPo po = inv.getArgument(0);
            if ("race_kb".equals(po.getRepoCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "KNOWLEDGE_BUSY");
            }
            return new KnowledgeRepositoryDtos.SyncResponse();
        });
        ReflectionTestUtils.setField(service, "repositoryMaintenance", repoMaintenance);

        assertDoesNotThrow(service::syncDueRepositories);
        verify(repoMaintenance, never()).submit(
                argThat(po -> "busy_kb".equals(po.getRepoCode())), nullable(String.class), any(Runnable.class));
        verify(repoMaintenance).submit(
                argThat(po -> "idle_kb".equals(po.getRepoCode())), nullable(String.class), any(Runnable.class));
        verify(repoMaintenance).submit(
                argThat(po -> "race_kb".equals(po.getRepoCode())), nullable(String.class), any(Runnable.class));
    }

    private KnowledgeRepositoryService service(FakeKnowledgeRepositoryMapper mapper) {
        return service(mapper, new FakeKnowledgeRepoMaintenanceCoordinator());
    }

    private KnowledgeRepositoryService service(FakeKnowledgeRepositoryMapper mapper,
                                               KnowledgeRepoMaintenanceCoordinator coordinator) {
        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        properties.setRootPath(tempDir.resolve("knowledge-repo").toString());
        KnowledgeRepositoryCredentialCipher cipher = new KnowledgeRepositoryCredentialCipher(properties);
        KnowledgeRepositoryAutoRegistrar autoRegistrar = new KnowledgeRepositoryAutoRegistrar(mapper, properties);
        KnowledgeRepositoryService service = new KnowledgeRepositoryService(
                mapper, properties, coordinator, cipher, autoRegistrar, List.of());
        UserContextBo admin = new UserContextBo();
        admin.setUserId("admin");
        admin.setRole(UserRoleEnum.ADMIN);
        ResourceAccessService resourceAccess = mock(ResourceAccessService.class);
        when(resourceAccess.current()).thenReturn(admin);
        when(resourceAccess.readable(admin)).thenAnswer(inv -> List.copyOf(mapper.selectAll()));
        when(resourceAccess.repositories(eq(admin), eq(true))).thenReturn(List.of());
        when(resourceAccess.requireRepository(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> {
                    String id = inv.getArgument(1);
                    KnowledgeRepositoryPo found = mapper.selectById(id);
                    return found != null ? found : mapper.selectByRepoCode(id);
                });
        ReflectionTestUtils.setField(service, "resourceAccess", resourceAccess);
        RepositoryMaintenanceService repoMaintenance = mock(RepositoryMaintenanceService.class);
        when(repoMaintenance.exclusiveRepository(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        ReflectionTestUtils.setField(service, "repositoryMaintenance", repoMaintenance);
        org.springframework.jdbc.core.JdbcTemplate permissionJdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(permissionJdbc.queryForList(org.mockito.ArgumentMatchers.anyString(), eq(String.class), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        ReflectionTestUtils.setField(service, "permissionJdbc", permissionJdbc);
        return service;
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
            KnowledgeRepositoryPo po = selectById(id);
            if (po == null) {
                return 0;
            }
            po.setStatus(status);
            po.setLastError(lastError);
            po.setUpdatedAt(updatedAt);
            return 1;
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
        private final boolean fullRebuildRunning;

        private FakeKnowledgeRepoMaintenanceCoordinator() {
            this(false);
        }

        private FakeKnowledgeRepoMaintenanceCoordinator(boolean fullRebuildRunning) {
            super(null, null, null, null, null, null, null, null);
            this.fullRebuildRunning = fullRebuildRunning;
        }

        @Override
        public boolean isFullRebuildRunning() {
            return fullRebuildRunning;
        }

        @Override
        public KnowledgeRepoSyncOutcome syncNowAsync(boolean fullRebuild) {
            return KnowledgeRepoSyncOutcome.accepted("queued");
        }
    }
}
