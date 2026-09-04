package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.mapper.ext.KnowledgeRepositoryTaskMapper;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.service.rag.knowledge.repository.KnowledgeRepositoryService;
import io.github.jerryt92.j2agent.service.security.ResourceAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredRole(UserRoleEnum.KNOWLEDGE_ADMIN)
@RequestMapping("/v1/rest/j2agent/knowledge/repositories")
public class KnowledgeRepositoryController {
    @org.springframework.beans.factory.annotation.Autowired
    private ResourceAccessService access;
    @org.springframework.beans.factory.annotation.Autowired
    private KnowledgeRepositoryTaskMapper taskMapper;
    private final KnowledgeRepositoryService service;

    public KnowledgeRepositoryController(KnowledgeRepositoryService service, KnowledgeRepositoryTaskMapper taskMapper) {
        this.service = service;
        this.taskMapper = taskMapper;
    }

    @GetMapping
    public ResponseEntity<KnowledgeRepositoryDtos.ListResponse> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeRepositoryDtos.Item> get(@PathVariable String id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping
    public ResponseEntity<KnowledgeRepositoryDtos.Item> create(
            @RequestBody KnowledgeRepositoryDtos.UpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeRepositoryDtos.Item> update(
            @PathVariable String id,
            @RequestBody KnowledgeRepositoryDtos.UpsertRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/tasks")
    public java.util.List<java.util.Map<String, Object>> tasks(@PathVariable String id) {
        String repositoryId = access.requireRepository(access.current(), id, 2).getId();
        return taskMapper.selectRecentByRepository(repositoryId);
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<KnowledgeRepositoryDtos.SyncResponse> sync(@PathVariable String id) {
        KnowledgeRepositoryDtos.SyncResponse response = service.syncNow(id);
        if (!Boolean.TRUE.equals(response.getSuccess())) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }
}
