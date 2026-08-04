package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMaintenanceCoordinator;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoSyncOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KnowledgeRepositoryService 创建逻辑：同 URL 不同目录名可并存，目录名冲突拒绝。
 */
class KnowledgeRepositoryServiceCreateTest {
    @TempDir
    Path tempDir;

    @Test
    void createAllowsSameRemoteUrlWithDifferentRepoCodes() {
        FakeKnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper();
        KnowledgeRepositoryService service = service(mapper);

        service.create(request("kb_one", "https://example.com/docs.git"));
        service.create(request("kb_two", "https://example.com/docs.git"));

        assertEquals(2, mapper.rows.size());
        assertEquals("kb_one", mapper.rows.get(0).getRepoCode());
        assertEquals("kb_two", mapper.rows.get(1).getRepoCode());
        assertEquals("https://example.com/docs.git", mapper.rows.get(0).getRemoteUrl());
        assertEquals("https://example.com/docs.git", mapper.rows.get(1).getRemoteUrl());
    }

    @Test
    void createRejectsDuplicateRepoCodeRegardlessOfRemoteUrl() {
        FakeKnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper();
        mapper.rows.add(existing("kb_docs", "https://example.com/old.git"));
        KnowledgeRepositoryService service = service(mapper);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.create(request("kb_docs", "https://example.com/new.git")));

        assertEquals("repository config already exists", error.getReason());
    }

    @Test
    void createWithoutRepoCodeStillRejectsSameUrlBecauseDerivedRepoCodeMatches() {
        FakeKnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper();
        mapper.rows.add(existing("docs", "https://example.com/docs.git"));
        KnowledgeRepositoryService service = service(mapper);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.create(request(null, "https://example.com/docs.git")));

        assertEquals("repository config already exists", error.getReason());
    }

    @Test
    void createAppliesAdvancedConfigDefaults() {
        FakeKnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper();
        KnowledgeRepositoryService service = service(mapper);

        service.create(request("kb-docs", "https://example.com/docs.git"));

        KnowledgeRepositoryPo po = mapper.rows.getFirst();
        assertEquals(KnowledgeRepositoryConstants.TYPE_REMOTE, po.getType());
        assertEquals("{\"collectionName\":\"kb_kb_docs\",\"partitionNames\":[],\"minHeadingLevel\":3,\"filenameAsTitle\":true}",
                po.getMetadataConfig());
        assertEquals(60, po.getUpdateIntervalMinutes());
        assertEquals(true, po.getEnabled());
    }

    @Test
    void createSavesAndReturnsRemoteSubPaths() {
        FakeKnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper();
        KnowledgeRepositoryService service = service(mapper);
        KnowledgeRepositoryDtos.UpsertRequest request = request("kb-docs", "https://example.com/docs.git");
        request.setSubPaths(List.of("docs/", "knowledge_base\\common"));

        KnowledgeRepositoryDtos.Item item = service.create(request);

        assertEquals(List.of("docs", "knowledge_base/common"), item.getSubPaths());
        assertEquals("{\"subPaths\":[\"docs\",\"knowledge_base/common\"]}", mapper.rows.getFirst().getProtocolConfig());
    }

    @Test
    void createRejectsDuplicateRemoteSubPaths() {
        FakeKnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper();
        KnowledgeRepositoryService service = service(mapper);
        KnowledgeRepositoryDtos.UpsertRequest request = request("kb-docs", "https://example.com/docs.git");
        request.setSubPaths(List.of("docs", "docs/"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.create(request));

        assertEquals("subPaths cannot contain duplicate paths", error.getReason());
    }

    @Test
    void createRejectsNestedRemoteSubPaths() {
        FakeKnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper();
        KnowledgeRepositoryService service = service(mapper);
        KnowledgeRepositoryDtos.UpsertRequest request = request("kb-docs", "https://example.com/docs.git");
        request.setSubPaths(List.of("docs", "docs/api"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.create(request));

        assertEquals("subPaths cannot contain nested paths", error.getReason());
    }

    @Test
    void createRejectsInvalidRemoteSubPaths() {
        FakeKnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper();
        KnowledgeRepositoryService service = service(mapper);
        KnowledgeRepositoryDtos.UpsertRequest request = request("kb-docs", "https://example.com/docs.git");
        request.setSubPaths(List.of("docs/../api"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.create(request));

        assertEquals("subPaths must not contain . or .. segments", error.getReason());
    }

    @Test
    void remoteSyncSkipsKnowledgeIncrementalSyncWhenWatchDisabled() throws Exception {
        FakeKnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper();
        KnowledgeRepoProperties properties = properties();
        properties.setWatchEnabled(false);
        CountingKnowledgeRepoMaintenanceCoordinator coordinator = new CountingKnowledgeRepoMaintenanceCoordinator();
        KnowledgeRepositoryService service = service(mapper, properties, coordinator);

        service.create(request("kb-docs", "https://example.com/docs.git"));

        assertTrue(await(() -> mapper.rows.getFirst().getLastRevision() != null));
        assertEquals(KnowledgeRepositoryConstants.STATUS_SYNCED, mapper.rows.getFirst().getStatus());
        assertEquals(0, coordinator.syncNowAsyncCalls.get());
    }

    private KnowledgeRepositoryService service(FakeKnowledgeRepositoryMapper mapper) {
        return service(mapper, properties(), new FakeKnowledgeRepoMaintenanceCoordinator());
    }

    private KnowledgeRepositoryService service(FakeKnowledgeRepositoryMapper mapper,
                                               KnowledgeRepoProperties properties,
                                               KnowledgeRepoMaintenanceCoordinator coordinator) {
        KnowledgeRepositoryCredentialCipher cipher = new KnowledgeRepositoryCredentialCipher(properties);
        KnowledgeRepositorySyncer syncer = new SuccessfulSyncer();
        KnowledgeRepositoryAutoRegistrar autoRegistrar = new KnowledgeRepositoryAutoRegistrar(mapper, properties);
        return new KnowledgeRepositoryService(
                mapper, properties, coordinator, cipher, autoRegistrar, List.of(syncer));
    }

    private KnowledgeRepoProperties properties() {
        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        properties.setRootPath(tempDir.resolve("knowledge-repo").toString());
        return properties;
    }

    private boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private KnowledgeRepositoryDtos.UpsertRequest request(String repoCode, String remoteUrl) {
        KnowledgeRepositoryDtos.UpsertRequest request = new KnowledgeRepositoryDtos.UpsertRequest();
        request.setRepoCode(repoCode);
        request.setRemoteUrl(remoteUrl);
        request.setType(KnowledgeRepositoryConstants.TYPE_REMOTE);
        request.setProtocol("GIT");
        request.setEnabled(true);
        request.setUpdateIntervalMinutes(60);
        return request;
    }

    private KnowledgeRepositoryPo existing(String repoCode, String remoteUrl) {
        KnowledgeRepositoryPo po = new KnowledgeRepositoryPo();
        po.setId(repoCode + "-id");
        po.setRepoCode(repoCode);
        po.setProtocol("GIT");
        po.setType(KnowledgeRepositoryConstants.TYPE_REMOTE);
        po.setEnabled(true);
        po.setStatus(KnowledgeRepositoryConstants.STATUS_IDLE);
        po.setRemoteUrl(remoteUrl);
        po.setUpdateIntervalMinutes(60);
        po.setMetadataConfig("{\"collectionName\":\"kb_" + repoCode
                + "\",\"partitionNames\":[],\"minHeadingLevel\":3,\"filenameAsTitle\":true}");
        return po;
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

    private static class CountingKnowledgeRepoMaintenanceCoordinator extends KnowledgeRepoMaintenanceCoordinator {
        private final AtomicInteger syncNowAsyncCalls = new AtomicInteger();

        private CountingKnowledgeRepoMaintenanceCoordinator() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public KnowledgeRepoSyncOutcome syncNowAsync(boolean fullRebuild) {
            syncNowAsyncCalls.incrementAndGet();
            return KnowledgeRepoSyncOutcome.accepted("queued");
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    private static class SuccessfulSyncer implements KnowledgeRepositorySyncer {
        @Override
        public String protocol() {
            return "GIT";
        }

        @Override
        public KnowledgeRepositorySyncResult sync(KnowledgeRepositoryPo repository,
                                                  KnowledgeRepositoryDtos.CredentialConfig credentialConfig,
                                                  Path localPath) {
            return new KnowledgeRepositorySyncResult("revision", "message", "author", 1L);
        }
    }

    private static class FakeKnowledgeRepositoryMapper implements KnowledgeRepositoryMapper {
        private final List<KnowledgeRepositoryPo> rows = new ArrayList<>();

        @Override
        public List<KnowledgeRepositoryPo> selectAll() {
            return rows;
        }

        @Override
        public List<KnowledgeRepositoryPo> selectRemoteAll() {
            return rows;
        }

        @Override
        public List<KnowledgeRepositoryPo> selectEnabledAll() {
            return rows.stream().filter(row -> Boolean.TRUE.equals(row.getEnabled())).toList();
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
            return rows;
        }

        @Override
        public int insert(KnowledgeRepositoryPo po) {
            rows.add(po);
            return 1;
        }

        @Override
        public int updateConfig(KnowledgeRepositoryPo po) {
            return 1;
        }

        @Override
        public int updateStatus(String id, String status, String lastError, long updatedAt) {
            KnowledgeRepositoryPo po = selectById(id);
            if (po != null) {
                po.setStatus(status);
                po.setLastError(lastError);
                po.setUpdatedAt(updatedAt);
            }
            return po == null ? 0 : 1;
        }

        @Override
        public int updateSyncResult(KnowledgeRepositoryPo po) {
            KnowledgeRepositoryPo current = selectById(po.getId());
            if (current != null) {
                current.setStatus(po.getStatus());
                current.setLastRevision(po.getLastRevision());
                current.setLastRevisionMessage(po.getLastRevisionMessage());
                current.setLastRevisionAuthor(po.getLastRevisionAuthor());
                current.setLastRevisionTime(po.getLastRevisionTime());
                current.setLastSyncTime(po.getLastSyncTime());
                current.setLastError(po.getLastError());
                current.setUpdatedAt(po.getUpdatedAt());
            }
            return current == null ? 0 : 1;
        }

        @Override
        public int deleteById(String id) {
            return rows.removeIf(row -> id.equals(row.getId())) ? 1 : 0;
        }
    }
}
