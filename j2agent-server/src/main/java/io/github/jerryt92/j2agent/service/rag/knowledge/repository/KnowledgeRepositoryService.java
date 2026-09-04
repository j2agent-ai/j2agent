package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import com.alibaba.fastjson2.JSON;
import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.mapper.ext.ResourcePermissionMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMaintenanceCoordinator;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoSyncOutcome;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 知识库仓库业务服务。
 */
@Slf4j
@Service
public class KnowledgeRepositoryService {
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.jerryt92.j2agent.service.security.ResourceAccessService resourceAccess;

    @org.springframework.beans.factory.annotation.Autowired
    private RepositoryMaintenanceService repositoryMaintenance;
    @org.springframework.beans.factory.annotation.Autowired
    private ResourcePermissionMapper permissionMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.jerryt92.j2agent.service.security.ResourcePermissionCache permissions;

    private final KnowledgeRepositoryMapper mapper;
    private final KnowledgeRepoProperties properties;
    private final KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator;
    private final KnowledgeRepositoryCredentialCipher credentialCipher;
    private final KnowledgeRepositoryAutoRegistrar autoRegistrar;
    private final Map<String, KnowledgeRepositorySyncer> syncers;
    private final Set<String> runningRepositoryCodes = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor syncExecutor;

    public KnowledgeRepositoryService(KnowledgeRepositoryMapper mapper,
                                      KnowledgeRepoProperties properties,
                                      KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator,
                                      KnowledgeRepositoryCredentialCipher credentialCipher,
                                      KnowledgeRepositoryAutoRegistrar autoRegistrar,
                                      List<KnowledgeRepositorySyncer> syncers) {
        this.mapper = mapper;
        this.properties = properties;
        this.maintenanceCoordinator = maintenanceCoordinator;
        this.credentialCipher = credentialCipher;
        this.autoRegistrar = autoRegistrar;
        this.syncers = syncers.stream()
                .collect(Collectors.toUnmodifiableMap(syncer -> syncer.protocol().toUpperCase(Locale.ROOT), Function.identity()));
        this.syncExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "knowledge-repository-sync");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public KnowledgeRepositoryDtos.ListResponse list() {
        autoRegistrar.ensureLocalRepositoriesForExistingDirectories();
        List<KnowledgeRepositoryDtos.Item> items = resourceAccess.readable(resourceAccess.current()).stream()
                .map(this::toItem)
                .sorted(Comparator.comparing(KnowledgeRepositoryDtos.Item::getRepoCode))
                .toList();
        KnowledgeRepositoryDtos.ListResponse response = new KnowledgeRepositoryDtos.ListResponse();
        response.setData(items);
        return response;
    }

    public KnowledgeRepositoryDtos.Item get(String repoCodeOrId) {
        KnowledgeRepositoryPo po = resourceAccess.requireRepository(resourceAccess.current(), repoCodeOrId, 2);
        if (po == null) {
            po = mapper.selectByRepoCode(repoCodeOrId);
        }
        if (po == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge repository config not found");
        }
        return toItem(po);
    }

    public KnowledgeRepositoryDtos.Item create(KnowledgeRepositoryDtos.UpsertRequest request) {
        String type = normalizeType(request.getType());
        String remoteUrl = KnowledgeRepositoryConstants.TYPE_REMOTE.equals(type)
                ? requireText(request.getRemoteUrl(), "remoteUrl")
                : StringUtils.trimToNull(request.getRemoteUrl());
        validateRemoteTransport(remoteUrl);
        String repoCode = normalizeRepoCode(StringUtils.defaultIfBlank(
                request.getRepoCode(),
                KnowledgeRepositoryConstants.TYPE_REMOTE.equals(type) ? deriveRepoCodeFromRemoteUrl(remoteUrl) : null));
        if (mapper.selectByRepoCode(repoCode) != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repository config already exists");
        }
        Path repoPath = resolveRepoPath(repoCode);
        if (!resourceAccess.current().isKnowledgeAdmin() && Files.exists(repoPath))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repository directory already exists");
        if (KnowledgeRepositoryConstants.TYPE_REMOTE.equals(type)) {
            validateRemoteDirectory(repoPath, remoteUrl);
        }
        ensureRepositoryDirectory(repoPath);
        long now = System.currentTimeMillis();
        KnowledgeRepositoryPo po = new KnowledgeRepositoryPo();
        po.setId(UUIDv7Utils.randomUUIDv7());
        po.setCreatorUserId(resourceAccess.current().getUserId().trim());
        po.setIsPublic(false);
        po.setRepoCode(repoCode);
        applyConfig(po, request, type, remoteUrl, null, now);
        po.setStatus(KnowledgeRepositoryConstants.STATUS_IDLE);
        po.setCreatedAt(now);
        mapper.insert(po);
        if (KnowledgeRepositoryConstants.TYPE_REMOTE.equals(type)) {
            submitSync(po, "create");
        } else {
            repositoryMaintenance.submit(po, resourceAccess.current().getUserId(), () -> {
            });
        }
        return toItem(mapper.selectById(po.getId()));
    }

    public KnowledgeRepositoryDtos.Item update(String id, KnowledgeRepositoryDtos.UpsertRequest request) {
        resourceAccess.requireRepository(resourceAccess.current(), id, 1);
        return repositoryMaintenance.exclusiveRepository(id, () -> updateLocked(id, request));
    }

    private KnowledgeRepositoryDtos.Item updateLocked(String id, KnowledgeRepositoryDtos.UpsertRequest request) {
        KnowledgeRepositoryPo current = requireConfigured(id);
        if (java.util.Set.of("SYNCING", "REBUILDING", "DELETING").contains(current.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KNOWLEDGE_BUSY");
        if (StringUtils.isNotBlank(request.getRepoCode()) && !current.getRepoCode().equals(request.getRepoCode().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoCode cannot be changed");
        }
        String type = StringUtils.defaultIfBlank(current.getType(), KnowledgeRepositoryConstants.TYPE_REMOTE);
        if (StringUtils.isNotBlank(request.getType()) && !type.equals(normalizeType(request.getType()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type cannot be changed");
        }
        String remoteUrl = KnowledgeRepositoryConstants.TYPE_REMOTE.equals(type)
                ? requireText(request.getRemoteUrl(), "remoteUrl")
                : null;
        long now = System.currentTimeMillis();
        applyConfig(current, request, type, remoteUrl, current, now);
        mapper.updateConfig(current);
        repositoryMaintenance.submit(current, resourceAccess.current().getUserId(), () -> {
        });
        return toItem(mapper.selectById(id));
    }

    /**
     * 删除知识库：同一请求内先打断 Git 同步，再清理向量与目录并删除配置。
     */
    public void delete(String id) {
        resourceAccess.requireRepository(resourceAccess.current(), id, 0);
        repositoryMaintenance.interruptRunning(id);
        repositoryMaintenance.exclusiveRepository(id, () -> {
            deleteLocked(id);
            return null;
        });
    }

    private void deleteLocked(String id) {
        KnowledgeRepositoryPo po = requireConfigured(id);
        if (java.util.Set.of("REBUILDING", "DELETING").contains(po.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repository is syncing");
        }
        mapper.updateStatus(id, "DELETING", null, System.currentTimeMillis());
        var grantedUsers = permissionMapper.findGrantedUserIds(id);
        for (String uid : grantedUsers)
            permissions.mutate(uid, () -> permissionMapper.deleteKnowledgeGrantByUser(id, uid));
        repositoryMaintenance.cleanup(po);
        deleteRepositoryDirectory(resolveRepoPath(po.getRepoCode()));
        mapper.deleteById(po.getId());
        // Target data was cleaned before the repository configuration was removed.
    }

    public KnowledgeRepositoryDtos.SyncResponse syncNow(String id) {
        resourceAccess.requireRepository(resourceAccess.current(), id, 1);
        KnowledgeRepositoryPo po = requireConfigured(id);
        if (KnowledgeRepositoryConstants.TYPE_REMOTE.equals(normalizeType(po.getType()))) {
            return submitSync(po, "manual");
        }
        return repositoryMaintenance.submit(po, resourceAccess.current().getUserId(), () -> {
        });
    }

    @Scheduled(cron = "0 * * * * *")
    public void syncDueRepositories() {
        long now = System.currentTimeMillis();
        for (KnowledgeRepositoryPo po : mapper.selectDueRemote(now)) {
            if (!Boolean.TRUE.equals(po.getEnabled())) {
                continue;
            }
            if (Set.of("SYNCING", "REBUILDING", "DELETING").contains(po.getStatus())) {
                continue;
            }
            int interval = normalizeInterval(po.getUpdateIntervalMinutes());
            Long lastSyncTime = po.getLastSyncTime();
            if (lastSyncTime != null && now - lastSyncTime < interval * 60_000L) {
                continue;
            }
            try {
                submitSync(po, "schedule");
            } catch (ResponseStatusException e) {
                // 重建/同步进行中属预期冲突，不应让整轮定时任务失败
                if (e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                    log.debug("定时同步跳过忙库: repoCode={}, status={}", po.getRepoCode(), po.getStatus());
                    continue;
                }
                throw e;
            }
        }
    }

    private void applyConfig(KnowledgeRepositoryPo po,
                             KnowledgeRepositoryDtos.UpsertRequest request,
                             String type,
                             String remoteUrl,
                             KnowledgeRepositoryPo current,
                             long now) {
        validateRemoteTransport(remoteUrl);
        String repoCode = po.getRepoCode();
        po.setType(type);
        po.setProtocol(KnowledgeRepositoryConstants.TYPE_REMOTE.equals(type) ? normalizeProtocol(request.getProtocol()) : null);
        po.setEnabled(!Boolean.FALSE.equals(request.getEnabled()));
        po.setUpdateIntervalMinutes(normalizeInterval(request.getUpdateIntervalMinutes()));
        po.setRemoteUrl(remoteUrl);
        po.setDefaultBranch(KnowledgeRepositoryConstants.TYPE_REMOTE.equals(type)
                ? StringUtils.trimToNull(request.getDefaultBranch())
                : null);
        String protocolConfig = request.getProtocolConfig() == null
                ? current == null ? "{}" : StringUtils.defaultIfBlank(current.getProtocolConfig(), "{}")
                : JSON.toJSONString(request.getProtocolConfig());
        List<String> subPaths = request.getSubPaths() == null && current != null
                ? KnowledgeRepositorySubPathSupport.parseProtocolConfigSubPaths(current.getProtocolConfig())
                : request.getSubPaths();
        po.setProtocolConfig(KnowledgeRepositoryConstants.TYPE_REMOTE.equals(type)
                ? mergeSubPaths(protocolConfig, subPaths)
                : protocolConfig);
        po.setDisplayName(StringUtils.trimToNull(request.getDisplayName()));
        MetadataConfig currentMetadataConfig = parseMetadataConfig(current == null ? null : current.getMetadataConfig());
        String collectionName = normalizeCollectionName(StringUtils.defaultIfBlank(
                request.getCollectionName(),
                current == null ? KnowledgeRepositoryConstants.defaultCollectionName(repoCode) : currentMetadataConfig.collectionName()));
        List<String> partitionNames = request.getPartitionNames() == null && current != null
                ? currentMetadataConfig.partitionNames()
                : normalizePartitionNames(request.getPartitionNames());
        int minHeadingLevel = request.getMinHeadingLevel() == null && current != null
                ? normalizeMinHeadingLevel(currentMetadataConfig.minHeadingLevel())
                : normalizeMinHeadingLevel(request.getMinHeadingLevel());
        boolean filenameAsTitle = resolveFilenameAsTitle(request.getFilenameAsTitle(), currentMetadataConfig);
        po.setMetadataConfig(toMetadataConfigJson(new MetadataConfig(
                collectionName,
                partitionNames,
                minHeadingLevel,
                filenameAsTitle)));
        po.setCredentialConfigCipher(KnowledgeRepositoryConstants.TYPE_REMOTE.equals(type)
                ? resolveCredentialCipher(request.getCredentialConfig(), current)
                : null);
        po.setUpdatedAt(now);
    }

    private static void validateRemoteTransport(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return;
        if (!remoteUrl.matches("(?i)^(https?://|ssh://|git@)[^\\s]+$"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only HTTP(S) or SSH Git remotes are allowed");
    }

    private KnowledgeRepositoryDtos.SyncResponse submitSync(KnowledgeRepositoryPo po, String trigger) {
        return repositoryMaintenance.submit(po, null, () -> syncRepository(po, trigger));
    }

    private KnowledgeRepositoryDtos.SyncResponse unusedLegacySubmitSync(KnowledgeRepositoryPo po, String trigger) {
        KnowledgeRepositoryDtos.SyncResponse response = new KnowledgeRepositoryDtos.SyncResponse();
        if (!runningRepositoryCodes.add(po.getRepoCode())) {
            response.setSuccess(false);
            response.setMessage("知识库仓库正在同步中");
            return response;
        }
        long now = System.currentTimeMillis();
        mapper.updateStatus(po.getId(), KnowledgeRepositoryConstants.STATUS_SYNCING, null, now);
        syncExecutor.execute(() -> {
            try {
                syncRepository(po, trigger);
            } finally {
                runningRepositoryCodes.remove(po.getRepoCode());
            }
        });
        response.setSuccess(true);
        response.setMessage("已提交后台同步任务");
        return response;
    }

    private void syncRepository(KnowledgeRepositoryPo po, String trigger) {
        try {
            KnowledgeRepositorySyncer syncer = syncers.get(StringUtils.defaultString(po.getProtocol()).toUpperCase(Locale.ROOT));
            if (syncer == null) {
                throw new IllegalStateException("不支持的知识库协议: " + po.getProtocol());
            }
            KnowledgeRepositoryDtos.CredentialConfig credentialConfig =
                    credentialCipher.decrypt(po.getCredentialConfigCipher());
            KnowledgeRepositorySyncResult result = syncer.sync(po, credentialConfig, resolveRepoPath(po.getRepoCode()));
            long doneAt = System.currentTimeMillis();
            po.setStatus(KnowledgeRepositoryConstants.STATUS_SYNCED);
            po.setLastRevision(result.revision());
            po.setLastRevisionMessage(result.revisionMessage());
            po.setLastRevisionAuthor(result.revisionAuthor());
            po.setLastRevisionTime(result.revisionTime());
            po.setLastSyncTime(doneAt);
            po.setLastError(null);
            po.setUpdatedAt(doneAt);
            mapper.updateSyncResult(po);
            if (!properties.isWatchEnabled()) {
                log.info("知识库仓库同步完成，跳过知识库增量同步: repoCode={}, trigger={}, revision={}, watchEnabled=false",
                        po.getRepoCode(), trigger, result.revision());
                return;
            }
            // RepositoryMaintenanceService performs scoped indexing after the pull.
            log.info("知识库仓库同步完成: repoCode={}, trigger={}, revision={}", po.getRepoCode(), trigger, result.revision());
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            long failedAt = System.currentTimeMillis();
            String message = StringUtils.defaultIfBlank(e.getMessage(), "知识库仓库同步失败");
            po.setStatus(KnowledgeRepositoryConstants.STATUS_FAILED);
            po.setLastSyncTime(failedAt);
            po.setLastError(message);
            po.setUpdatedAt(failedAt);
            mapper.updateSyncResult(po);
            log.warn("知识库仓库同步失败: repoCode={}, trigger={}, error={}", po.getRepoCode(), trigger, message, e);
            throw new IllegalStateException(message, e);
        }
    }

    private KnowledgeRepositoryDtos.Item toItem(KnowledgeRepositoryPo po) {
        String type = normalizeType(po.getType());
        Path repoPath = resolveRepoPath(po.getRepoCode());
        KnowledgeRepositoryDtos.Item item = new KnowledgeRepositoryDtos.Item();
        item.setId(po.getId());
        item.setCreatorUserId(po.getCreatorUserId());
        item.setIsPublic(po.getIsPublic());
        boolean owner = resourceAccess.current().isKnowledgeAdmin() || resourceAccess.current().getUserId().trim().equals(po.getCreatorUserId() == null ? "" : po.getCreatorUserId().trim());
        boolean manage = owner || resourceAccess.repositories(resourceAccess.current(), true).stream().anyMatch(p -> p.getId().equals(po.getId()));
        item.setCanManage(manage);
        item.setCanShare(owner);
        item.setRepoCode(po.getRepoCode());
        item.setType(type);
        item.setProtocol(po.getProtocol());
        item.setEnabled(po.getEnabled());
        item.setReadonly(!manage);
        item.setLocalPath(manage ? repoPath.toAbsolutePath().normalize().toString() : null);
        item.setUpdateIntervalMinutes(po.getUpdateIntervalMinutes());
        item.setStatus(resolveDisplayStatus(repoPath, po));
        // 全局完全重建时统一展示状态，有库权限的用户均可见
        if (maintenanceCoordinator != null && maintenanceCoordinator.isFullRebuildRunning()) {
            item.setStatus(KnowledgeRepositoryConstants.STATUS_GLOBAL_REBUILDING);
        }
        item.setRemoteUrl(manage ? po.getRemoteUrl() : null);
        item.setDefaultBranch(po.getDefaultBranch());
        item.setLastRevision(po.getLastRevision());
        item.setLastRevisionMessage(po.getLastRevisionMessage());
        item.setLastRevisionAuthor(po.getLastRevisionAuthor());
        item.setLastRevisionTime(po.getLastRevisionTime());
        item.setLastSyncTime(po.getLastSyncTime());
        item.setLastError(po.getLastError());
        item.setProtocolConfig(parseProtocolConfig(po.getProtocolConfig()));
        item.setSubPaths(KnowledgeRepositorySubPathSupport.parseProtocolConfigSubPaths(po.getProtocolConfig()));
        item.setHasCredential(StringUtils.isNotBlank(po.getCredentialConfigCipher()));
        MetadataConfig metadataConfig = parseMetadataConfig(po.getMetadataConfig());
        item.setCollections(StringUtils.isBlank(metadataConfig.collectionName()) ? List.of() : List.of(metadataConfig.collectionName()));
        item.setDisplayName(po.getDisplayName());
        item.setCollectionName(metadataConfig.collectionName());
        item.setPartitionNames(metadataConfig.partitionNames());
        item.setMinHeadingLevel(metadataConfig.minHeadingLevel());
        item.setFilenameAsTitle(metadataConfig.filenameAsTitle());
        return item;
    }

    private String resolveDisplayStatus(Path repoPath, KnowledgeRepositoryPo po) {
        if (KnowledgeRepositoryConstants.STATUS_SYNCING.equals(po.getStatus())) {
            return KnowledgeRepositoryConstants.STATUS_SYNCING;
        }
        if (!Files.isDirectory(repoPath)) {
            return KnowledgeRepositoryConstants.STATUS_DIRECTORY_MISSING;
        }
        return StringUtils.defaultIfBlank(po.getStatus(), KnowledgeRepositoryConstants.STATUS_IDLE);
    }

    private void validateRemoteDirectory(Path repoPath, String remoteUrl) {
        if (Files.exists(repoPath)) {
            if (!Files.isDirectory(repoPath.resolve(".git"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repository directory already exists and is not a Git repository");
            }
            if (!sameOriginRemoteUrl(repoPath, remoteUrl)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repository directory already exists with different Git remote");
            }
        }
    }

    private void ensureRepositoryDirectory(Path repoPath) {
        try {
            Files.createDirectories(repoPath);
        } catch (IOException e) {
            throw new IllegalStateException("创建知识库一级目录失败: " + repoPath, e);
        }
    }

    private void triggerKnowledgeSync(String trigger, String repoCode) {
        KnowledgeRepoSyncOutcome outcome = maintenanceCoordinator.syncNowAsync(false);
        if (!outcome.succeeded()) {
            log.warn("知识库配置变更后提交增量同步失败: trigger={}, repoCode={}, message={}",
                    trigger, repoCode, outcome.message());
        }
    }

    private String resolveCredentialCipher(KnowledgeRepositoryDtos.CredentialConfig request,
                                           KnowledgeRepositoryPo current) {
        String encrypted = credentialCipher.encrypt(request);
        if (StringUtils.isNotBlank(encrypted)) {
            return encrypted;
        }
        return current == null ? null : current.getCredentialConfigCipher();
    }

    private Map<String, Object> parseProtocolConfig(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        return new LinkedHashMap<>(JSON.parseObject(json));
    }

    private String mergeSubPaths(String protocolConfig, List<String> subPaths) {
        try {
            return KnowledgeRepositorySubPathSupport.mergeProtocolConfig(protocolConfig, subPaths);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private KnowledgeRepositoryPo requireConfigured(String id) {
        KnowledgeRepositoryPo po = mapper.selectById(id);
        if (po == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge repository config not found");
        }
        return po;
    }

    private Path resolveRepoPath(String repoCode) {
        if (StringUtils.isBlank(properties.getRootPath())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledge repo root-path is required");
        }
        return Path.of(properties.getRootPath()).resolve(normalizeRepoCode(repoCode)).toAbsolutePath().normalize();
    }

    private void deleteRepositoryDirectory(Path repoPath) {
        Path rootPath = Path.of(properties.getRootPath()).toAbsolutePath().normalize();
        if (repoPath.equals(rootPath) || !repoPath.startsWith(rootPath)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid repository directory");
        }
        if (!Files.exists(repoPath)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(repoPath)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("删除知识库目录失败: " + repoPath, e);
        }
    }

    private boolean sameOriginRemoteUrl(Path repoPath, String remoteUrl) {
        try (Git git = Git.open(repoPath.toFile())) {
            String existing = git.getRepository().getConfig().getString("remote", "origin", "url");
            return StringUtils.equals(existing, remoteUrl);
        } catch (IOException e) {
            throw new IllegalStateException("读取已有 Git 知识库远程地址失败: " + repoPath, e);
        }
    }

    private String normalizeType(String type) {
        String normalized = StringUtils.defaultIfBlank(type, KnowledgeRepositoryConstants.TYPE_REMOTE)
                .trim()
                .toUpperCase(Locale.ROOT);
        if (!KnowledgeRepositoryConstants.TYPE_REMOTE.equals(normalized)
                && !KnowledgeRepositoryConstants.TYPE_LOCAL_FILE.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be LOCAL_FILE or REMOTE");
        }
        return normalized;
    }

    private String normalizeRepoCode(String repoCode) {
        String normalized = requireText(repoCode, "repoCode").trim();
        if (normalized.length() > 128 || ".".equals(normalized) || "..".equals(normalized)
                || normalized.contains("/") || normalized.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoCode must be a valid first-level directory name");
        }
        return normalized;
    }

    private String normalizeCollectionName(String collectionName) {
        String normalized = requireText(collectionName, "collectionName").trim();
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "collectionName must match [A-Za-z_][A-Za-z0-9_]{0,127}");
        }
        return normalized;
    }

    private String deriveRepoCodeFromRemoteUrl(String remoteUrl) {
        String path = remoteUrl;
        try {
            URI uri = URI.create(remoteUrl);
            if (StringUtils.isNotBlank(uri.getPath())) {
                path = uri.getPath();
            }
        } catch (IllegalArgumentException ignored) {
            int colonIndex = remoteUrl.lastIndexOf(':');
            if (colonIndex >= 0 && colonIndex + 1 < remoteUrl.length()) {
                path = remoteUrl.substring(colonIndex + 1);
            }
        }
        String normalizedPath = StringUtils.substringBefore(path, "?");
        normalizedPath = StringUtils.substringBefore(normalizedPath, "#");
        normalizedPath = StringUtils.stripEnd(normalizedPath, "/");
        String lastSegment = StringUtils.substringAfterLast(normalizedPath, "/");
        if (StringUtils.isBlank(lastSegment)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoCode is required when remoteUrl has no repository name");
        }
        String decoded = URLDecoder.decode(lastSegment, StandardCharsets.UTF_8);
        return StringUtils.removeEnd(decoded, ".git");
    }

    private String normalizeProtocol(String protocol) {
        String normalized = StringUtils.defaultIfBlank(protocol, KnowledgeRepositoryConstants.PROTOCOL_GIT)
                .trim()
                .toUpperCase(Locale.ROOT);
        if (!KnowledgeRepositoryConstants.PROTOCOL_GIT.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only GIT protocol is supported");
        }
        return normalized;
    }

    private int normalizeInterval(Integer interval) {
        if (interval == null) {
            return KnowledgeRepositoryConstants.DEFAULT_UPDATE_INTERVAL_MINUTES;
        }
        return Math.max(1, interval);
    }

    private int normalizeMinHeadingLevel(Integer minHeadingLevel) {
        if (minHeadingLevel == null) {
            return KnowledgeRepositoryConstants.DEFAULT_MIN_HEADING_LEVEL;
        }
        if (minHeadingLevel < 1 || minHeadingLevel > 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minHeadingLevel must be between 1 and 3");
        }
        return minHeadingLevel;
    }

    private boolean resolveFilenameAsTitle(Boolean requested, MetadataConfig current) {
        if (requested != null) {
            return requested;
        }
        if (current != null && current.filenameAsTitle() != null) {
            return current.filenameAsTitle();
        }
        return KnowledgeRepositoryConstants.DEFAULT_FILENAME_AS_TITLE;
    }

    private String toMetadataConfigJson(MetadataConfig config) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("collectionName", config.collectionName());
        json.put("partitionNames", config.partitionNames());
        json.put("minHeadingLevel", config.minHeadingLevel());
        json.put("filenameAsTitle", config.filenameAsTitle());
        return JSON.toJSONString(json);
    }

    private MetadataConfig parseMetadataConfig(String json) {
        if (StringUtils.isBlank(json)) {
            return new MetadataConfig(
                    null,
                    List.of(),
                    KnowledgeRepositoryConstants.DEFAULT_MIN_HEADING_LEVEL,
                    KnowledgeRepositoryConstants.DEFAULT_FILENAME_AS_TITLE);
        }
        Map<String, Object> config = JSON.parseObject(json);
        return new MetadataConfig(
                StringUtils.trimToNull((String) config.get("collectionName")),
                parsePartitionNames(config.get("partitionNames")),
                normalizeMinHeadingLevel(toInteger(config.get("minHeadingLevel"))),
                config.containsKey("filenameAsTitle")
                        ? Boolean.parseBoolean(String.valueOf(config.get("filenameAsTitle")))
                        : KnowledgeRepositoryConstants.DEFAULT_FILENAME_AS_TITLE);
    }

    private List<String> parsePartitionNames(Object raw) {
        if (raw == null) {
            return List.of();
        }
        return normalizePartitionNames(JSON.parseArray(JSON.toJSONString(raw), String.class));
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private List<String> normalizePartitionNames(List<String> partitionNames) {
        if (partitionNames == null || partitionNames.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String raw : partitionNames) {
            if (StringUtils.isBlank(raw)) {
                continue;
            }
            String name = raw.trim();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partitionNames must match [A-Za-z_][A-Za-z0-9_]{0,127}");
            }
            names.add(name);
        }
        return new ArrayList<>(names);
    }

    private String requireText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return value.trim();
    }

    private record MetadataConfig(
            String collectionName,
            List<String> partitionNames,
            Integer minHeadingLevel,
            Boolean filenameAsTitle
    ) {
    }
}
