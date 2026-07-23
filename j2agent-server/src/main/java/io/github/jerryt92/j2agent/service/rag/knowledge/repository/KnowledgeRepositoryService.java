package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMaintenanceCoordinator;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
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
    private static final String DISPLAY_NAME_CONFIG_KEY = "display_name";
    private static final String LEGACY_ALIAS_CONFIG_KEY = "alias";
    private static final String COLLECTION_ALIASES_CONFIG_KEY = "collectionAliases";

    private final KnowledgeRepositoryMapper mapper;
    private final KnowledgeRepoProperties properties;
    private final KnowledgeRepoMetadataService metadataService;
    private final KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator;
    private final KnowledgeRepositoryCredentialCipher credentialCipher;
    private final Map<String, KnowledgeRepositorySyncer> syncers;
    private final Set<String> runningRepositoryCodes = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor syncExecutor;

    public KnowledgeRepositoryService(KnowledgeRepositoryMapper mapper,
                                      KnowledgeRepoProperties properties,
                                      KnowledgeRepoMetadataService metadataService,
                                      KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator,
                                      KnowledgeRepositoryCredentialCipher credentialCipher,
                                      List<KnowledgeRepositorySyncer> syncers) {
        this.mapper = mapper;
        this.properties = properties;
        this.metadataService = metadataService;
        this.maintenanceCoordinator = maintenanceCoordinator;
        this.credentialCipher = credentialCipher;
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
        Map<String, KnowledgeRepositoryPo> remoteByCode = mapper.selectRemoteAll().stream()
                .collect(Collectors.toMap(KnowledgeRepositoryPo::getRepoCode, Function.identity(), (left, right) -> left));
        Map<String, Path> repositoryPathsByCode = new LinkedHashMap<>();
        for (Path path : listTopLevelRepositoryDirs()) {
            repositoryPathsByCode.put(path.getFileName().toString(), path);
        }
        for (KnowledgeRepositoryPo remoteConfig : remoteByCode.values()) {
            repositoryPathsByCode.putIfAbsent(remoteConfig.getRepoCode(), resolveRepoPath(remoteConfig.getRepoCode()));
        }
        List<KnowledgeRepositoryDtos.Item> items = repositoryPathsByCode.entrySet().stream()
                .map(entry -> toDirectoryItem(entry.getValue(), remoteByCode.get(entry.getKey())))
                .sorted(Comparator.comparing(KnowledgeRepositoryDtos.Item::getRepoCode))
                .toList();
        KnowledgeRepositoryDtos.ListResponse response = new KnowledgeRepositoryDtos.ListResponse();
        response.setData(items);
        return response;
    }

    public KnowledgeRepositoryDtos.Item get(String repoCodeOrId) {
        KnowledgeRepositoryPo po = mapper.selectById(repoCodeOrId);
        if (po == null) {
            po = mapper.selectByRepoCode(repoCodeOrId);
        }
        String repoCode = po == null ? repoCodeOrId : po.getRepoCode();
        Path repoPath = resolveRepoPath(repoCode);
        if (po == null && !Files.isDirectory(repoPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge repository directory not found");
        }
        return toDirectoryItem(repoPath, po);
    }

    public KnowledgeRepositoryDtos.Item create(KnowledgeRepositoryDtos.UpsertRequest request) {
        String remoteUrl = requireText(request.getRemoteUrl(), "remoteUrl");
        String repoCode = normalizeRepoCode(StringUtils.defaultIfBlank(
                request.getRepoCode(),
                deriveRepoCodeFromRemoteUrl(remoteUrl)));
        if (mapper.selectByRepoCode(repoCode) != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repository remote config already exists");
        }
        Path repoPath = resolveRepoPath(repoCode);
        if (Files.exists(repoPath)) {
            if (!Files.isDirectory(repoPath.resolve(".git"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repository directory already exists and is not a Git repository");
            }
            if (!sameOriginRemoteUrl(repoPath, remoteUrl)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repository directory already exists with different Git remote");
            }
        }
        try {
            Files.createDirectories(repoPath);
        } catch (IOException e) {
            throw new IllegalStateException("创建知识库一级目录失败: " + repoPath, e);
        }
        long now = System.currentTimeMillis();
        KnowledgeRepositoryPo po = new KnowledgeRepositoryPo();
        po.setId(UUIDv7Utils.randomUUIDv7());
        po.setRepoCode(repoCode);
        po.setProtocol(normalizeProtocol(request.getProtocol()));
        po.setEnabled(!Boolean.FALSE.equals(request.getEnabled()));
        po.setUpdateIntervalMinutes(normalizeInterval(request.getUpdateIntervalMinutes()));
        po.setStatus(KnowledgeRepositoryConstants.STATUS_IDLE);
        po.setRemoteUrl(remoteUrl);
        po.setDefaultBranch(StringUtils.trimToNull(request.getDefaultBranch()));
        po.setProtocolConfig(toProtocolConfigJson(request.getProtocolConfig(), request.getDisplayName(), request.getCollectionAliases(), null));
        po.setCredentialConfigCipher(resolveCredentialCipher(request.getCredentialConfig(), null));
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        mapper.insert(po);
        submitSync(po, "create");
        return toDirectoryItem(repoPath, mapper.selectById(po.getId()));
    }

    public KnowledgeRepositoryDtos.Item update(String id, KnowledgeRepositoryDtos.UpsertRequest request) {
        KnowledgeRepositoryPo current = requireRemote(id);
        if (StringUtils.isNotBlank(request.getRepoCode()) && !current.getRepoCode().equals(request.getRepoCode().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoCode cannot be changed");
        }
        long now = System.currentTimeMillis();
        current.setProtocol(normalizeProtocol(request.getProtocol()));
        current.setEnabled(!Boolean.FALSE.equals(request.getEnabled()));
        current.setUpdateIntervalMinutes(normalizeInterval(request.getUpdateIntervalMinutes()));
        current.setRemoteUrl(requireText(request.getRemoteUrl(), "remoteUrl"));
        current.setDefaultBranch(StringUtils.trimToNull(request.getDefaultBranch()));
        current.setProtocolConfig(toProtocolConfigJson(request.getProtocolConfig(), request.getDisplayName(), request.getCollectionAliases(), current));
        current.setCredentialConfigCipher(resolveCredentialCipher(request.getCredentialConfig(), current));
        current.setUpdatedAt(now);
        mapper.updateConfig(current);
        return toDirectoryItem(resolveRepoPath(current.getRepoCode()), mapper.selectById(id));
    }

    public void delete(String id) {
        KnowledgeRepositoryPo po = requireRemote(id);
        if (runningRepositoryCodes.contains(po.getRepoCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repository is syncing");
        }
        deleteRepositoryDirectory(resolveRepoPath(po.getRepoCode()));
        mapper.deleteById(po.getId());
        KnowledgeRepoSyncOutcome outcome = maintenanceCoordinator.syncNowAsync(false);
        if (!outcome.succeeded()) {
            log.warn("删除远程知识库目录后提交增量同步失败: repoCode={}, message={}", po.getRepoCode(), outcome.message());
        }
    }

    public KnowledgeRepositoryDtos.SyncResponse syncNow(String id) {
        return submitSync(requireRemote(id), "manual");
    }

    @Scheduled(cron = "0 * * * * *")
    public void syncDueRepositories() {
        long now = System.currentTimeMillis();
        for (KnowledgeRepositoryPo po : mapper.selectRemoteAll()) {
            if (!Boolean.TRUE.equals(po.getEnabled())) {
                continue;
            }
            int interval = normalizeInterval(po.getUpdateIntervalMinutes());
            Long lastSyncTime = po.getLastSyncTime();
            if (lastSyncTime != null && now - lastSyncTime < interval * 60_000L) {
                continue;
            }
            submitSync(po, "schedule");
        }
    }

    private KnowledgeRepositoryDtos.SyncResponse submitSync(KnowledgeRepositoryPo po, String trigger) {
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
            KnowledgeRepoSyncOutcome outcome = maintenanceCoordinator.syncNowAsync(false);
            if (!outcome.succeeded()) {
                throw new IllegalStateException(outcome.message());
            }
            log.info("知识库仓库同步完成: repoCode={}, trigger={}, revision={}", po.getRepoCode(), trigger, result.revision());
        } catch (Exception e) {
            long failedAt = System.currentTimeMillis();
            String message = StringUtils.defaultIfBlank(e.getMessage(), "知识库仓库同步失败");
            po.setStatus(KnowledgeRepositoryConstants.STATUS_FAILED);
            po.setLastSyncTime(failedAt);
            po.setLastError(message);
            po.setUpdatedAt(failedAt);
            mapper.updateSyncResult(po);
            log.warn("知识库仓库同步失败: repoCode={}, trigger={}, error={}", po.getRepoCode(), trigger, message, e);
        }
    }

    private KnowledgeRepositoryDtos.Item toDirectoryItem(Path repoPath, KnowledgeRepositoryPo remoteConfig) {
        String repoCode = repoPath.getFileName().toString();
        RepoInfoSummary info = readRepoInfoSummary(repoPath);
        KnowledgeRepositoryDtos.Item item = new KnowledgeRepositoryDtos.Item();
        item.setId(remoteConfig == null ? repoCode : remoteConfig.getId());
        item.setRepoCode(repoCode);
        item.setType(remoteConfig == null ? KnowledgeRepositoryConstants.TYPE_LOCAL_FILE : KnowledgeRepositoryConstants.TYPE_REMOTE);
        item.setProtocol(remoteConfig == null ? null : remoteConfig.getProtocol());
        item.setEnabled(remoteConfig == null || Boolean.TRUE.equals(remoteConfig.getEnabled()));
        item.setReadonly(remoteConfig == null);
        item.setLocalPath(repoPath.toAbsolutePath().normalize().toString());
        item.setUpdateIntervalMinutes(remoteConfig == null ? null : remoteConfig.getUpdateIntervalMinutes());
        item.setStatus(resolveDisplayStatus(repoPath, remoteConfig));
        item.setRemoteUrl(remoteConfig == null ? null : remoteConfig.getRemoteUrl());
        item.setDefaultBranch(remoteConfig == null ? null : remoteConfig.getDefaultBranch());
        item.setLastRevision(remoteConfig == null ? null : remoteConfig.getLastRevision());
        item.setLastRevisionMessage(remoteConfig == null ? null : remoteConfig.getLastRevisionMessage());
        item.setLastRevisionAuthor(remoteConfig == null ? null : remoteConfig.getLastRevisionAuthor());
        item.setLastRevisionTime(remoteConfig == null ? null : remoteConfig.getLastRevisionTime());
        item.setLastSyncTime(remoteConfig == null ? null : remoteConfig.getLastSyncTime());
        item.setLastError(remoteConfig == null ? null : remoteConfig.getLastError());
        Map<String, Object> protocolConfig = remoteConfig == null ? Map.of() : parseProtocolConfig(remoteConfig.getProtocolConfig());
        item.setProtocolConfig(protocolConfig);
        item.setHasCredential(remoteConfig != null && StringUtils.isNotBlank(remoteConfig.getCredentialConfigCipher()));
        item.setCollections(info.collections());
        item.setDisplayName(remoteConfig == null ? null : extractDisplayName(protocolConfig));
        item.setCollectionAliases(remoteConfig == null ? Map.of() : extractCollectionAliases(protocolConfig));
        item.setMinHeadingLevel(info.minHeadingLevel());
        item.setFilenameAsTitle(info.filenameAsTitle());
        return item;
    }

    private String resolveDisplayStatus(Path repoPath, KnowledgeRepositoryPo remoteConfig) {
        if (remoteConfig == null) {
            return KnowledgeRepositoryConstants.STATUS_SYNCED;
        }
        if (KnowledgeRepositoryConstants.STATUS_SYNCING.equals(remoteConfig.getStatus())) {
            return KnowledgeRepositoryConstants.STATUS_SYNCING;
        }
        if (!Files.isDirectory(repoPath)) {
            return KnowledgeRepositoryConstants.STATUS_DIRECTORY_MISSING;
        }
        return StringUtils.defaultIfBlank(remoteConfig.getStatus(), KnowledgeRepositoryConstants.STATUS_IDLE);
    }

    private List<Path> listTopLevelRepositoryDirs() {
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null || !Files.isDirectory(rootPath)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(rootPath)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描知识库一级目录失败", e);
        }
    }

    private RepoInfoSummary readRepoInfoSummary(Path repoPath) {
        Set<String> collections = new LinkedHashSet<>();
        Integer minHeadingLevel = null;
        Boolean filenameAsTitle = null;
        if (!Files.isDirectory(repoPath)) {
            return new RepoInfoSummary(
                    List.of(),
                    KnowledgeRepoMetadataService.DEFAULT_MIN_HEADING_LEVEL,
                    KnowledgeRepoMetadataService.DEFAULT_FILENAME_AS_TITLE);
        }
        try (Stream<Path> stream = Files.walk(repoPath)) {
            for (Path infoJsonPath : stream.filter(Files::isRegularFile)
                    .filter(path -> "info.json".equals(path.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                JSONObject info = JSON.parseObject(Files.readString(infoJsonPath));
                String collection = info.getString("collection_name");
                if (StringUtils.isNotBlank(collection)) {
                    collections.add(collection.trim());
                }
                if (minHeadingLevel == null) {
                    minHeadingLevel = info.getInteger("min_heading_level");
                }
                if (filenameAsTitle == null && info.containsKey("filename_as_title")) {
                    filenameAsTitle = info.getBoolean("filename_as_title");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取知识库 info.json 失败: " + repoPath, e);
        }
        return new RepoInfoSummary(
                new ArrayList<>(collections),
                minHeadingLevel == null ? KnowledgeRepoMetadataService.DEFAULT_MIN_HEADING_LEVEL : minHeadingLevel,
                filenameAsTitle == null ? KnowledgeRepoMetadataService.DEFAULT_FILENAME_AS_TITLE : filenameAsTitle);
    }

    private String resolveCredentialCipher(KnowledgeRepositoryDtos.CredentialConfig request,
                                           KnowledgeRepositoryPo current) {
        String encrypted = credentialCipher.encrypt(request);
        if (StringUtils.isNotBlank(encrypted)) {
            return encrypted;
        }
        return current == null ? null : current.getCredentialConfigCipher();
    }

    private String toProtocolConfigJson(Map<String, Object> protocolConfig,
                                        String displayName,
                                        Map<String, String> collectionAliases,
                                        KnowledgeRepositoryPo current) {
        if (protocolConfig == null && displayName == null && collectionAliases == null) {
            return current == null ? "{}" : StringUtils.defaultIfBlank(current.getProtocolConfig(), "{}");
        }
        Map<String, Object> next = protocolConfig == null
                ? new LinkedHashMap<>(parseProtocolConfig(current == null ? null : current.getProtocolConfig()))
                : new LinkedHashMap<>(protocolConfig);
        String normalizedDisplayName = displayName == null ? extractDisplayName(next) : StringUtils.trimToNull(displayName);
        next.remove(LEGACY_ALIAS_CONFIG_KEY);
        if (normalizedDisplayName == null) {
            next.remove(DISPLAY_NAME_CONFIG_KEY);
        } else {
            next.put(DISPLAY_NAME_CONFIG_KEY, normalizedDisplayName);
        }
        Map<String, String> aliases = collectionAliases == null
                ? extractCollectionAliases(next)
                : normalizeCollectionAliases(collectionAliases);
        if (aliases.isEmpty()) {
            next.remove(COLLECTION_ALIASES_CONFIG_KEY);
        } else {
            next.put(COLLECTION_ALIASES_CONFIG_KEY, aliases);
        }
        return JSON.toJSONString(next);
    }

    private Map<String, Object> parseProtocolConfig(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        return new LinkedHashMap<>(JSON.parseObject(json));
    }

    private String extractDisplayName(Map<String, Object> protocolConfig) {
        if (protocolConfig == null || protocolConfig.isEmpty()) {
            return null;
        }
        Object raw = protocolConfig.get(DISPLAY_NAME_CONFIG_KEY);
        if (raw == null) {
            raw = protocolConfig.get(LEGACY_ALIAS_CONFIG_KEY);
        }
        return raw == null ? null : StringUtils.trimToNull(raw.toString());
    }

    private Map<String, String> extractCollectionAliases(Map<String, Object> protocolConfig) {
        if (protocolConfig == null || protocolConfig.isEmpty()) {
            return Map.of();
        }
        Object raw = protocolConfig.get(COLLECTION_ALIASES_CONFIG_KEY);
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, String> aliases = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> {
                String collection = key == null ? null : key.toString();
                String alias = value == null ? null : value.toString();
                if (StringUtils.isNotBlank(collection) && StringUtils.isNotBlank(alias)) {
                    aliases.put(collection.trim(), alias.trim());
                }
            });
            return aliases;
        }
        return Map.of();
    }

    private Map<String, String> normalizeCollectionAliases(Map<String, String> collectionAliases) {
        if (collectionAliases == null || collectionAliases.isEmpty()) {
            return Map.of();
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        collectionAliases.forEach((collection, alias) -> {
            if (StringUtils.isNotBlank(collection) && StringUtils.isNotBlank(alias)) {
                aliases.put(collection.trim(), alias.trim());
            }
        });
        return aliases;
    }

    private KnowledgeRepositoryPo requireRemote(String id) {
        KnowledgeRepositoryPo po = mapper.selectById(id);
        if (po == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge repository remote config not found");
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

    private String normalizeRepoCode(String repoCode) {
        String normalized = requireText(repoCode, "repoCode").trim();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9_-]{1,127}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoCode must match [A-Za-z0-9][A-Za-z0-9_-]{1,127}");
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

    private String requireText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return value.trim();
    }

    private record RepoInfoSummary(
            List<String> collections,
            Integer minHeadingLevel,
            Boolean filenameAsTitle
    ) {
    }
}
