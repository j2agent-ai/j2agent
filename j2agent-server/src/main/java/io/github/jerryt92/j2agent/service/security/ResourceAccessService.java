package io.github.jerryt92.j2agent.service.security;

import com.alibaba.fastjson2.JSON;
import io.github.jerryt92.j2agent.mapper.ext.ResourcePermissionMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeCollectionSelection;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resource metadata remains authoritative in SQL; cached grants only contribute IDs.
 */
@Service
public class ResourceAccessService {
    private final ResourcePermissionCache cache;
    private final ResourcePermissionMapper permissionMapper;
    private final LoginService login;

    public ResourceAccessService(ResourcePermissionCache cache, ResourcePermissionMapper permissionMapper, LoginService login) {
        this.cache = cache;
        this.permissionMapper = permissionMapper;
        this.login = login;
    }

    public UserContextBo current() {
        return requireIdentity(login.getSession());
    }

    public static UserContextBo requireIdentity(UserContextBo user) {
        if (user == null || user.getUserId() == null || user.getUserId().isBlank() || user.getRole() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        return user;
    }

    public Set<String> allowedAgents(UserContextBo user) {
        requireIdentity(user);
        Set<String> ids = new HashSet<>(permissionMapper.selectAgentIds(user.isAdmin()));
        if (!user.isAdmin()) ids.addAll(cache.read(user.getUserId()).agents());
        return ids;
    }

    public void requireAgent(UserContextBo user, String agentId) {
        requireIdentity(user);
        if (!user.isAdmin() && !allowedAgents(user).contains(agentId)) deny("AGENT_ACCESS_DENIED");
    }

    public List<KnowledgeRepositoryPo> readable(UserContextBo user) {
        return repositories(user, false);
    }

    public List<KnowledgeRepositoryPo> repositories(UserContextBo user, boolean manage) {
        requireIdentity(user);
        if (knowledgeElevated(user))
            return permissionMapper.selectRepositories(user.getUserId().trim(), true, true, Set.of());
        var grants = cache.read(user.getUserId());
        Set<String> ids = manage ? grants.manage() : grants.read();
        return permissionMapper.selectRepositories(user.getUserId().trim(), manage, false, ids);
    }

    public KnowledgeRepositoryPo requireRepository(UserContextBo user, String id, int level) {
        for (KnowledgeRepositoryPo po : repositories(user, level < 2)) {
            if (po.getId().equals(id) || po.getRepoCode().equals(id)) {
                if (level == 0 && !knowledgeElevated(user) && !user.getUserId().trim().equals(trim(po.getCreatorUserId())))
                    deny("KNOWLEDGE_ACCESS_DENIED");
                return po;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "KNOWLEDGE_ACCESS_DENIED");
    }

    public void requireSource(UserContextBo user, String source) {
        String normalized = source == null ? "" : source.replace('\\', '/');
        if (normalized.startsWith("/") || Arrays.asList(normalized.split("/")).contains(".."))
            deny("KNOWLEDGE_ACCESS_DENIED");
        int slash = normalized.indexOf('/');
        if (slash < 1) deny("KNOWLEDGE_ACCESS_DENIED");
        requireRepository(user, normalized.substring(0, slash), 2);
    }

    public static String collection(KnowledgeRepositoryPo po) {
        var metadata = JSON.parseObject(po.getMetadataConfig());
        String value = metadata == null ? null : metadata.getString("collectionName");
        return value == null || value.isBlank() ? "kb_" + po.getRepoCode() : value;
    }

    public List<String> selectRepositories(UserContextBo user, Collection<String> repositoryIds) {
        List<String> selected = new ArrayList<>();
        for (String id : new LinkedHashSet<>(repositoryIds)) {
            KnowledgeRepositoryPo po = requireRepository(user, id, 2);
            requireReady(po);
            selected.add(KnowledgeCollectionSelection.encode(po.getRepoCode(), collection(po)));
        }
        return selected;
    }

    public List<String> resolveCollections(UserContextBo user, Collection<String> requested, boolean explicit) {
        List<KnowledgeRepositoryPo> readable = readable(user);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : requested) {
            String value = StringUtils.trimToNull(raw);
            if (value == null) {
                if (explicit) {
                    deny("KNOWLEDGE_ACCESS_DENIED");
                }
                continue;
            }
            if (!matchCollectionSelection(readable, value, result, explicit)) {
                if (explicit) {
                    deny("KNOWLEDGE_ACCESS_DENIED");
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * 将前端选择值解析为可读 collection；兼容 repositoryId / repoCode 直传。
     */
    private boolean matchCollectionSelection(List<KnowledgeRepositoryPo> readable,
                                             String raw,
                                             LinkedHashSet<String> result,
                                             boolean explicit) {
        KnowledgeCollectionSelection.Parsed parsed = KnowledgeCollectionSelection.parse(raw);
        if (parsed != null) {
            for (KnowledgeRepositoryPo po : readable) {
                if (collection(po).equals(parsed.collection())
                        && (parsed.repoCode() == null || po.getRepoCode().equals(parsed.repoCode()))) {
                    return addResolvedCollection(po, result, explicit);
                }
            }
        }
        for (KnowledgeRepositoryPo po : readable) {
            if (po.getId().equals(raw) || po.getRepoCode().equals(raw)) {
                return addResolvedCollection(po, result, explicit);
            }
        }
        return false;
    }

    private boolean addResolvedCollection(KnowledgeRepositoryPo po,
                                          LinkedHashSet<String> result,
                                          boolean explicit) {
        if (!isReady(po)) {
            if (explicit) {
                requireReady(po);
            }
            return false;
        }
        result.add(KnowledgeCollectionSelection.encode(po.getRepoCode(), collection(po)));
        return true;
    }

    private static boolean isReady(KnowledgeRepositoryPo po) {
        return !Set.of("REBUILDING", "REBUILD_FAILED", "DELETING").contains(String.valueOf(po.getStatus()));
    }

    private static void requireReady(KnowledgeRepositoryPo po) {
        if (!isReady(po)) throw new ResponseStatusException(HttpStatus.CONFLICT, "KNOWLEDGE_UNAVAILABLE");
    }

    /**
     * 系统管理员或知识库管理员，在知识库域内等同全局可见/可管。
     */
    private static boolean knowledgeElevated(UserContextBo user) {
        return user.isAdmin() || user.isKnowledgeAdmin();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    public static void deny(String code) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, code);
    }
}
