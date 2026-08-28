package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.service.security.ApiAccessKeyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理 API 专用用户及其一对一访问密钥。 */
@RestController
@RequestMapping("/v1/rest/j2agent/api-keys")
@RequiredRole(UserRoleEnum.ADMIN)
public class ApiAccessKeyController {
    private final ApiAccessKeyService service;
    public ApiAccessKeyController(ApiAccessKeyService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<List<ApiAccessKeyService.KeyDto>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    public ResponseEntity<ApiAccessKeyService.CreatedKey> create(@RequestBody CreateRequest request) {
        return ResponseEntity.ok(service.create(request.keyName(), request.username(), request.role()));
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<Void> updateRole(@PathVariable String id, @RequestBody RoleRequest request) {
        service.updateRole(id, request.role());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }

    public record CreateRequest(String keyName, String username, Integer role) { }
    public record RoleRequest(Integer role) { }
}
