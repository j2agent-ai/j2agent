package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.model.EmbeddingRuntimeStatusDto;
import io.github.jerryt92.j2agent.model.ProviderConfigDto;
import io.github.jerryt92.j2agent.model.ProviderConfigUpsertDto;
import io.github.jerryt92.j2agent.server.api.ProviderConfigApi;
import io.github.jerryt92.j2agent.config.provider.ApiProviderConfigService;
import io.github.jerryt92.j2agent.config.provider.ApiProviderConfigService.ProviderConfigView;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingChangeOrchestrator;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingRuntimeStatus;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMaintenanceCoordinator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型提供商配置（LLM/Embedding）REST 端点。
 */
@RestController
@RequiredRole(UserRoleEnum.ADMIN)
public class ProviderConfigController implements ProviderConfigApi {

    private final ApiProviderConfigService service;
    private final EmbeddingService embeddingService;
    private final KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator;
    private final EmbeddingChangeOrchestrator embeddingChangeOrchestrator;

    public ProviderConfigController(ApiProviderConfigService service,
                                    EmbeddingService embeddingService,
                                    KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator,
                                    EmbeddingChangeOrchestrator embeddingChangeOrchestrator) {
        this.service = service;
        this.embeddingService = embeddingService;
        this.maintenanceCoordinator = maintenanceCoordinator;
        this.embeddingChangeOrchestrator = embeddingChangeOrchestrator;
    }

    @Override
    public ResponseEntity<List<ProviderConfigDto>> listProviderConfigs(String apiType) {
        List<ProviderConfigDto> dtos = service.list(apiType).stream()
                .map(ProviderConfigController::toDto)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @Override
    public ResponseEntity<ProviderConfigDto> createProviderConfig(ProviderConfigUpsertDto body) {
        String apiType = body.getApiType() == null ? null : body.getApiType().getValue();
        boolean enabled = true;
        boolean makeCurrent = body.getMakeCurrent() != null && body.getMakeCurrent();
        ProviderConfigView view = service.create(
                apiType,
                body.getConfigName(),
                body.getProviderType(),
                body.getConfig(),
                body.getDescription(),
                enabled,
                makeCurrent);
        return ResponseEntity.ok(toDto(view));
    }

    @Override
    public ResponseEntity<ProviderConfigDto> updateProviderConfig(String id, ProviderConfigUpsertDto body) {
        boolean enabled = true;
        ProviderConfigView view = service.update(
                id,
                body.getConfigName(),
                body.getProviderType(),
                body.getConfig(),
                body.getDescription(),
                enabled);
        return ResponseEntity.ok(toDto(view));
    }

    @Override
    public ResponseEntity<Void> deleteProviderConfig(String id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<ProviderConfigDto> activateProviderConfig(String id) {
        return ResponseEntity.ok(toDto(service.activate(id)));
    }

    @Override
    public ResponseEntity<ProviderConfigDto> copyProviderConfig(String id) {
        return ResponseEntity.ok(toDto(service.copy(id)));
    }

    @Override
    public ResponseEntity<EmbeddingRuntimeStatusDto> getEmbeddingRuntime() {
        return ResponseEntity.ok(toRuntimeDto(embeddingService.getRuntimeStatus(maintenanceCoordinator.isExclusiveSyncActive())));
    }

    @Override
    public ResponseEntity<EmbeddingRuntimeStatusDto> probeEmbeddingRuntime() {
        embeddingChangeOrchestrator.enqueueProbeOnly();
        return ResponseEntity.ok(toRuntimeDto(embeddingService.getRuntimeStatus(maintenanceCoordinator.isExclusiveSyncActive())));
    }

    private static EmbeddingRuntimeStatusDto toRuntimeDto(EmbeddingRuntimeStatus status) {
        return new EmbeddingRuntimeStatusDto()
                .ready(status.ready())
                .dimension(status.dimension())
                .checkEmbeddingHash(status.checkEmbeddingHash())
                .modelName(status.modelName())
                .providerType(status.providerType())
                .lastProbeTime(status.lastProbeTime())
                .probeError(status.probeError())
                .fullRebuildRunning(status.fullRebuildRunning())
                .embeddingBatchSize(status.embeddingBatchSize());
    }

    /**
     * 服务层视图转 DTO；apiKey 已在服务层脱敏。
     */
    private static ProviderConfigDto toDto(ProviderConfigView view) {
        ProviderConfigDto dto = new ProviderConfigDto();
        dto.setId(view.id());
        dto.setApiType(ProviderConfigDto.ApiTypeEnum.fromValue(view.apiType()));
        dto.setConfigName(view.configName());
        dto.setProviderType(view.providerType());
        dto.setConfig(view.config());
        dto.setEnabled(view.enabled());
        dto.setIsCurrent(view.isCurrent());
        dto.setDescription(view.description());
        dto.setCreateTime(view.createTime());
        dto.setUpdateTime(view.updateTime());
        return dto;
    }
}
