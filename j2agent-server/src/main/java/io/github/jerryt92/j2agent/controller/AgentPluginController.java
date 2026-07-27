package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.model.AgentInstallResult;
import io.github.jerryt92.j2agent.model.AgentPluginPackageDto;
import io.github.jerryt92.j2agent.model.AgentPluginStatusDto;
import io.github.jerryt92.j2agent.model.AgentReloadResult;
import io.github.jerryt92.j2agent.server.api.AgentPluginApi;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentPluginInstallService;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentPluginRegistry;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentPluginReloadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 外部 Agent 插件 JAR 管理接口。
 */
@RestController
@RequiredRole(UserRoleEnum.ADMIN)
public class AgentPluginController implements AgentPluginApi {

    private final AgentPluginReloadService agentPluginReloadService;
    private final AgentPluginInstallService agentPluginInstallService;

    public AgentPluginController(AgentPluginReloadService agentPluginReloadService,
                                 AgentPluginInstallService agentPluginInstallService) {
        this.agentPluginReloadService = agentPluginReloadService;
        this.agentPluginInstallService = agentPluginInstallService;
    }

    @Override
    public ResponseEntity<AgentPluginStatusDto> getAgentPlugins() {
        AgentPluginRegistry.AgentPluginStatus status = agentPluginReloadService.getStatus();
        AgentPluginStatusDto dto = new AgentPluginStatusDto()
                .jarFiles(status.jarFiles())
                .loadedAgentIds(status.loadedAgentIds())
                .packages(toPackageDtos(status.packages()));
        return ResponseEntity.ok(dto);
    }

    @Override
    public ResponseEntity<AgentInstallResult> installAgentPackage(MultipartFile file, Boolean replace) {
        boolean replaceRequested = Boolean.TRUE.equals(replace);
        AgentPluginInstallService.AgentInstallOutcome outcome =
                agentPluginInstallService.installPackage(file, replaceRequested);
        AgentInstallResult result = new AgentInstallResult()
                .success(outcome.success())
                .message(outcome.message())
                .conflict(outcome.conflict())
                .conflictingAgentIds(outcome.conflictingAgentIds())
                .existingAgentDir(outcome.existingAgentDir())
                .incomingAgentIds(outcome.incomingAgentIds())
                .jarFiles(outcome.jarFiles())
                .loadedAgentIds(outcome.loadedAgentIds());
        if (outcome.conflict()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        }
        if (!outcome.success()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<AgentReloadResult> deleteAgentPackage(String agentDir) {
        AgentPluginRegistry.AgentPluginReloadOutcome outcome = agentPluginInstallService.deletePackage(agentDir);
        AgentReloadResult result = toReloadResult(outcome);
        if (!outcome.success()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<AgentReloadResult> reloadAgentPackage(String agentDir, Boolean rebuildSimpleRag) {
        boolean rebuild = Boolean.TRUE.equals(rebuildSimpleRag);
        AgentPluginRegistry.AgentPluginReloadOutcome outcome =
                agentPluginReloadService.reloadPackage(agentDir, rebuild);
        AgentReloadResult result = toReloadResult(outcome);
        if (!outcome.success()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<AgentReloadResult> reloadAgents(Boolean rebuildSimpleRag) {
        boolean rebuild = Boolean.TRUE.equals(rebuildSimpleRag);
        AgentPluginRegistry.AgentPluginReloadOutcome outcome = agentPluginReloadService.reload(rebuild);
        AgentReloadResult result = toReloadResult(outcome);
        if (!outcome.success()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    private static AgentReloadResult toReloadResult(AgentPluginRegistry.AgentPluginReloadOutcome outcome) {
        return new AgentReloadResult()
                .success(outcome.success())
                .message(outcome.message())
                .jarFiles(outcome.jarFiles())
                .loadedAgentIds(outcome.loadedAgentIds());
    }

    private static List<AgentPluginPackageDto> toPackageDtos(
            List<AgentPluginRegistry.InstalledPackageInfo> packages) {
        if (packages == null || packages.isEmpty()) {
            return List.of();
        }
        return packages.stream()
                .map(pkg -> new AgentPluginPackageDto()
                        .agentDir(pkg.agentDir())
                        .jarName(pkg.jarName())
                        .agentIds(pkg.agentIds())
                        .loaded(pkg.loaded()))
                .toList();
    }
}
