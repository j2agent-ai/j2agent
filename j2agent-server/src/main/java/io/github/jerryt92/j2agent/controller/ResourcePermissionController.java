package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.security.*;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/v1/rest/j2agent")
public class ResourcePermissionController {
    private final ResourceAccessService access;
    private final ResourcePermissionCache cache;
    private final JdbcTemplate jdbc;
    private final AgentRouter router;
    public ResourcePermissionController(ResourceAccessService access, ResourcePermissionCache cache, JdbcTemplate jdbc, AgentRouter router) {
        this.access = access; this.cache = cache; this.jdbc = jdbc; this.router = router;
    }
    private String authorize(String type, String id) {
        if (type.equals("agents")) {
            if (!access.current().isAdmin()) ResourceAccessService.deny("AGENT_ACCESS_DENIED");
            router.route(id); return id;
        }
        UserContextBo user = access.current();
        if (!user.isKnowledgeAdmin()) ResourceAccessService.deny("KNOWLEDGE_ACCESS_DENIED");
        return access.requireRepository(user, id, 2).getId();
    }
    @GetMapping({"/agents/{id}/permissions", "/knowledge/repositories/{id}/permissions"})
    public List<Map<String,Object>> list(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request) {
        boolean agent = request.getRequestURI().contains("/agents/");
        String resource = authorize(agent ? "agents" : "knowledge", id);
        String table = agent ? "user_agent_permission" : "user_knowledge_permission";
        String column = agent ? "agent_id" : "knowledge_repository_id";
        return jdbc.queryForList("SELECT user_id AS \"userId\", permission_level AS \"permissionLevel\", expires_at AS \"expiresAt\", " +
                "(expires_at IS NOT NULL AND expires_at<=?) AS expired FROM " + table + " WHERE " + column + "=?", System.currentTimeMillis(), resource);
    }
    @PutMapping({"/agents/{id}/permissions/{userId}", "/knowledge/repositories/{id}/permissions/{userId}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(@PathVariable String id, @PathVariable String userId, @RequestBody Map<String,Object> body,
                      jakarta.servlet.http.HttpServletRequest request) {
        boolean agent = request.getRequestURI().contains("/agents/");
        String resource = authorize(agent ? "agents" : "knowledge", id);
        int level = body.get("permissionLevel") instanceof Number n ? n.intValue() : -1;
        if (level != 2 && (agent || level != 1)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid permissionLevel");
        String table = agent ? "user_agent_permission" : "user_knowledge_permission";
        String column = agent ? "agent_id" : "knowledge_repository_id";
        cache.mutate(userId, () -> {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id=? AND role=3", Integer.class, userId);
            if (count == null || count != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ordinary user required");
            List<Map<String,Object>> old = jdbc.queryForList("SELECT expires_at FROM " + table + " WHERE user_id=? AND " + column + "=?", userId, resource);
            Object raw = body.containsKey("expiresAt") ? body.get("expiresAt") : old.isEmpty() ? null : old.get(0).get("expires_at");
            Long expiry = raw == null ? null : raw instanceof Number n ? n.longValue() : -1L;
            long now = System.currentTimeMillis();
            if (body.containsKey("expiresAt") && expiry != null && expiry <= now)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresAt must be in the future");
            jdbc.update("INSERT INTO " + table + " (id,user_id," + column + ",permission_level,expires_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?) " +
                    "ON CONFLICT(user_id," + column + ") DO UPDATE SET permission_level=EXCLUDED.permission_level,expires_at=EXCLUDED.expires_at,updated_at=EXCLUDED.updated_at",
                    UUIDv7Utils.randomUUIDv7(), userId, resource, level, expiry, now, now);
        });
    }
    @DeleteMapping({"/agents/{id}/permissions/{userId}", "/knowledge/repositories/{id}/permissions/{userId}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, @PathVariable String userId, jakarta.servlet.http.HttpServletRequest request) {
        boolean agent = request.getRequestURI().contains("/agents/");
        String resource = authorize(agent ? "agents" : "knowledge", id);
        cache.mutate(userId, () -> jdbc.update("DELETE FROM " + (agent ? "user_agent_permission WHERE agent_id" : "user_knowledge_permission WHERE knowledge_repository_id") + "=? AND user_id=?", resource, userId));
    }
    @PutMapping({"/agents/{id}/visibility", "/knowledge/repositories/{id}/visibility"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void visibility(@PathVariable String id, @RequestBody Map<String,Object> body, jakarta.servlet.http.HttpServletRequest request) {
        boolean agent = request.getRequestURI().contains("/agents/");
        String resource = authorize(agent ? "agents" : "knowledge", id);
        if (!(body.get("isPublic") instanceof Boolean value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "isPublic required");
        jdbc.update("UPDATE " + (agent ? "agent_access_config" : "knowledge_repository") + " SET is_public=?,updated_at=? WHERE " + (agent ? "agent_id" : "id") + "=?", value, System.currentTimeMillis(), resource);
    }

    @GetMapping({"/agents/{id}/visibility", "/knowledge/repositories/{id}/visibility"})
    public Map<String,Object> visibilityInfo(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request) {
        boolean agent=request.getRequestURI().contains("/agents/");
        String resource=authorize(agent?"agents":"knowledge",id);
        Boolean value=jdbc.queryForObject("SELECT is_public FROM "+(agent?"agent_access_config WHERE agent_id":"knowledge_repository WHERE id")+"=?",Boolean.class,resource);
        return Map.of("isPublic",Boolean.TRUE.equals(value));
    }
}
