package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.AuditContextDetailDto;
import io.github.jerryt92.j2agent.model.AuditContextListDto;
import io.github.jerryt92.j2agent.model.AuditTokenRecordListDto;
import io.github.jerryt92.j2agent.model.AuditTokenSummaryDto;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.server.api.AuditApi;
import io.github.jerryt92.j2agent.service.audit.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员审计接口：Token 用量与跨用户聊天记录（独立 /audit/*，不扩大用户态 /context*）。
 */
@RestController
@RequiredRole(UserRoleEnum.ADMIN)
public class AuditController implements AuditApi {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public ResponseEntity<AuditTokenSummaryDto> getAuditTokenSummary(
            String userId, String username, Long from, Long to, Integer offset, Integer limit) {
        return ResponseEntity.ok(auditService.getTokenSummary(userId, username, from, to, offset, limit));
    }

    @Override
    public ResponseEntity<AuditTokenRecordListDto> getAuditTokenRecords(
            String userId,
            String agentId,
            String modelName,
            String callKind,
            String usageStatus,
            Long from,
            Long to,
            Integer offset,
            Integer limit) {
        return ResponseEntity.ok(auditService.getTokenRecords(
                userId, agentId, modelName, callKind, usageStatus, from, to, offset, limit));
    }

    @Override
    public ResponseEntity<AuditContextListDto> getAuditContexts(
            String userId,
            String title,
            String agentId,
            Long from,
            Long to,
            Integer offset,
            Integer limit) {
        return ResponseEntity.ok(auditService.getContexts(userId, title, agentId, from, to, offset, limit));
    }

    @Override
    public ResponseEntity<AuditContextDetailDto> getAuditContext(String contextId, String agentId) {
        return ResponseEntity.ok(auditService.getContext(contextId, agentId));
    }
}
