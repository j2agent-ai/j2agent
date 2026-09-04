package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.mapper.ext.ResourcePermissionMapper;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.security.ResourceAccessService;
import io.github.jerryt92.j2agent.service.security.ResourcePermissionCache;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/rest/j2agent")
public class ResourcePermissionController {
    private final ResourceAccessService access;
    private final ResourcePermissionCache cache;
    private final ResourcePermissionMapper permissionMapper;
    private final AgentRouter router;

    public ResourcePermissionController(ResourceAccessService access, ResourcePermissionCache cache, ResourcePermissionMapper permissionMapper, AgentRouter router) {
        this.access = access;
        this.cache = cache;
        this.permissionMapper = permissionMapper;
        this.router = router;
    }

    private String authorize(String type, String id) {
        if (type.equals("agents")) {
            if (!access.current().isAdmin()) ResourceAccessService.deny("AGENT_ACCESS_DENIED");
            router.route(id);
            return id;
        }
        UserContextBo user = access.current();
        if (!user.isKnowledgeAdmin()) ResourceAccessService.deny("KNOWLEDGE_ACCESS_DENIED");
        return access.requireRepository(user, id, 2).getId();
    }

    @GetMapping({"/agents/{id}/permissions", "/knowledge/repositories/{id}/permissions"})
    public List<Map<String, Object>> list(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request) {
        boolean agent = request.getRequestURI().contains("/agents/");
        String resource = authorize(agent ? "agents" : "knowledge", id);
        return agent ? permissionMapper.listAgentGrants(resource, System.currentTimeMillis())
                : permissionMapper.listKnowledgeGrants(resource, System.currentTimeMillis());
    }

    @PutMapping({"/agents/{id}/permissions/{username}", "/knowledge/repositories/{id}/permissions/{username}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(@PathVariable String id, @PathVariable String username, @RequestBody Map<String, Object> body,
                      jakarta.servlet.http.HttpServletRequest request) {
        boolean agent = request.getRequestURI().contains("/agents/");
        String resource = authorize(agent ? "agents" : "knowledge", id);
        int level = body.get("permissionLevel") instanceof Number n ? n.intValue() : -1;
        if (level != 2 && (agent || level != 1))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid permissionLevel");
        String userId = requireOrdinaryUserId(username);
        cache.mutate(userId, () -> {
            Long oldExpiry = agent ? permissionMapper.findAgentExpiry(userId, resource)
                    : permissionMapper.findKnowledgeExpiry(userId, resource);
            Object raw = body.containsKey("expiresAt") ? body.get("expiresAt") : oldExpiry;
            Long expiry = raw == null ? null : raw instanceof Number n ? n.longValue() : -1L;
            long now = System.currentTimeMillis();
            if (body.containsKey("expiresAt") && expiry != null && expiry <= now)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresAt must be in the future");
            if (agent)
                permissionMapper.upsertAgentGrant(UUIDv7Utils.randomUUIDv7(), userId, resource, level, expiry, now);
            else
                permissionMapper.upsertKnowledgeGrant(UUIDv7Utils.randomUUIDv7(), userId, resource, level, expiry, now);
        });
    }

    @DeleteMapping({"/agents/{id}/permissions/{username}", "/knowledge/repositories/{id}/permissions/{username}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, @PathVariable String username, jakarta.servlet.http.HttpServletRequest request) {
        boolean agent = request.getRequestURI().contains("/agents/");
        String resource = authorize(agent ? "agents" : "knowledge", id);
        String userId = requireOrdinaryUserId(username);
        cache.mutate(userId, () -> {
            if (agent) permissionMapper.deleteAgentGrant(resource, userId);
            else permissionMapper.deleteKnowledgeGrant(resource, userId);
        });
    }

    private String requireOrdinaryUserId(String username) {
        if (username == null || username.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username is required");
        String userId = permissionMapper.findOrdinaryUserId(username.trim());
        if (userId == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ordinary user required");
        return userId;
    }

    @PutMapping({"/agents/{id}/visibility", "/knowledge/repositories/{id}/visibility"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void visibility(@PathVariable String id, @RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletRequest request) {
        boolean agent = request.getRequestURI().contains("/agents/");
        String resource = authorize(agent ? "agents" : "knowledge", id);
        if (!(body.get("isPublic") instanceof Boolean value))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "isPublic required");
        if (agent) permissionMapper.updateAgentVisibility(resource, value, System.currentTimeMillis());
        else permissionMapper.updateRepositoryVisibility(resource, value, System.currentTimeMillis());
    }

    @GetMapping({"/agents/{id}/visibility", "/knowledge/repositories/{id}/visibility"})
    public Map<String, Object> visibilityInfo(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request) {
        boolean agent = request.getRequestURI().contains("/agents/");
        String resource = authorize(agent ? "agents" : "knowledge", id);
        Boolean value = agent ? permissionMapper.agentIsPublic(resource) : permissionMapper.repositoryIsPublic(resource);
        return Map.of("isPublic", Boolean.TRUE.equals(value));
    }
}
