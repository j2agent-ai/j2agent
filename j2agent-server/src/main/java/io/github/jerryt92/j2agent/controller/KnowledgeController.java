package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.KnowledgeAddDto;
import io.github.jerryt92.j2agent.model.KnowledgeCollectionListDto;
import io.github.jerryt92.j2agent.model.KnowledgeGetListDto;
import io.github.jerryt92.j2agent.model.KnowledgeRetrieveItemDto;
import io.github.jerryt92.j2agent.model.KnowledgeRetrieveResponseDto;
import io.github.jerryt92.j2agent.model.KnowledgeSyncFileStatusDto;
import io.github.jerryt92.j2agent.model.KnowledgeSyncResult;
import io.github.jerryt92.j2agent.model.KnowledgeSyncStatusDto;
import io.github.jerryt92.j2agent.server.api.KnowledgeApi;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingRuntimeStatus;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMaintenanceCoordinator;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoSyncOutcome;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoSyncProgressTracker;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoSyncStatusSnapshot;
import io.github.jerryt92.j2agent.service.rag.retrieval.Retriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@RestController
@RequiredRole(RequiredRole.ADMIN)
public class KnowledgeController implements KnowledgeApi {

    private final KnowledgeService knowledgeService;
    private final Retriever retriever;
    private final KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator;
    private final EmbeddingService embeddingService;

    public KnowledgeController(KnowledgeService knowledgeService,
                               Retriever retriever,
                               KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator,
                               EmbeddingService embeddingService) {
        this.knowledgeService = knowledgeService;
        this.retriever = retriever;
        this.maintenanceCoordinator = maintenanceCoordinator;
        this.embeddingService = embeddingService;
    }

    @Override
    public ResponseEntity<KnowledgeGetListDto> getKnowledge(Integer offset, Integer limit, String collection, String search) {
        return ResponseEntity.ok(knowledgeService.getKnowledge(offset, limit, search, collection, null));
    }

    @Override
    @RequiredRole(RequiredRole.USER)
    public ResponseEntity<KnowledgeCollectionListDto> getKnowledgeCollections() {
        return ResponseEntity.ok(knowledgeService.getKnowledgeCollections());
    }

    @Override
    public ResponseEntity<KnowledgeSyncResult> syncKnowledge(Boolean fullRebuild) {
        boolean fullRebuildRequested = Boolean.TRUE.equals(fullRebuild);
        KnowledgeRepoSyncOutcome outcome = maintenanceCoordinator.syncNowAsync(fullRebuildRequested);
        KnowledgeSyncResult result = new KnowledgeSyncResult()
                .success(outcome.succeeded())
                .message(outcome.message());
        if (!outcome.succeeded()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<KnowledgeSyncStatusDto> getKnowledgeSyncStatus() {
        KnowledgeRepoSyncStatusSnapshot snapshot = maintenanceCoordinator.snapshotSyncStatus();
        EmbeddingRuntimeStatus runtime = embeddingService.getRuntimeStatus(maintenanceCoordinator.isExclusiveSyncActive());
        return ResponseEntity.ok(toSyncStatusDto(snapshot, runtime));
    }

    private static KnowledgeSyncStatusDto toSyncStatusDto(
            KnowledgeRepoSyncStatusSnapshot snapshot,
            EmbeddingRuntimeStatus runtime) {
        KnowledgeSyncStatusDto dto = new KnowledgeSyncStatusDto()
                .ready(runtime.ready())
                .dimension(runtime.dimension())
                .modelName(runtime.modelName())
                .providerType(runtime.providerType())
                .embeddingBatchSize(runtime.embeddingBatchSize())
                .lastProbeTime(runtime.lastProbeTime())
                .probeError(runtime.probeError())
                .taskType(KnowledgeSyncStatusDto.TaskTypeEnum.fromValue(snapshot.taskType().name()))
                .maintenanceActive(snapshot.maintenanceActive())
                .fullRebuildRunning(snapshot.fullRebuildRunning())
                .exclusiveSyncActive(snapshot.exclusiveSyncActive())
                .lastFailureMessage(snapshot.lastFailureMessage())
                .phase(KnowledgeSyncStatusDto.PhaseEnum.fromValue(snapshot.phase().name()))
                .totalCount(snapshot.totalCount())
                .processedCount(snapshot.processedCount())
                .currentFilePath(snapshot.currentFilePath());
        List<KnowledgeSyncFileStatusDto> files = snapshot.files().stream()
                .map(KnowledgeController::toSyncFileStatusDto)
                .toList();
        dto.setFiles(files);
        return dto;
    }

    private static KnowledgeSyncFileStatusDto toSyncFileStatusDto(
            KnowledgeRepoSyncProgressTracker.FileProgress file) {
        return new KnowledgeSyncFileStatusDto()
                .filePath(file.filePath())
                .changeType(KnowledgeSyncFileStatusDto.ChangeTypeEnum.fromValue(file.changeType().name()))
                .status(KnowledgeSyncFileStatusDto.StatusEnum.fromValue(file.status().name()))
                .collection(file.collection())
                .knowledgeCount(file.knowledgeCount())
                .errorMessage(file.errorMessage());
    }

    @Override
    public ResponseEntity<Void> putKnowledge(List<KnowledgeAddDto> knowledgeAddDto) {
        // 文件直入模式下禁用手工写入接口。
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @Override
    public ResponseEntity<Void> deleteKnowledge(List<String> requestBody) {
        // 文件直入模式下禁用手工删除接口。
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @Override
    public ResponseEntity<KnowledgeRetrieveResponseDto> retrieveKnowledge(String collection, String queryText, Integer topK) {
        List<KnowledgeRetrieveItemDto> knowledgeRetrieveItemDtos = retriever.retrieveKnowledge(queryText, topK, collection);
        KnowledgeRetrieveResponseDto knowledgeRetrieveResponseDto = new KnowledgeRetrieveResponseDto();
        knowledgeRetrieveResponseDto.setData(knowledgeRetrieveItemDtos);
        return ResponseEntity.ok(knowledgeRetrieveResponseDto);
    }

    @Override
    public ResponseEntity<Resource> getJsonTemplate() {
        HttpHeaders headers = new HttpHeaders();
        String fileName = "json-template.json";
        headers.setContentDisposition(ContentDisposition.builder("attachment")
                .filename(URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new ClassPathResource("static/json-template.json"));
    }

    @Override
    public ResponseEntity importKnowledgeByJson(MultipartFile jsonTemplate) {
        // 文件直入模式下禁用 JSON 导入接口。
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }
}
