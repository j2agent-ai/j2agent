package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.model.EmbeddingsRequestDto;
import io.github.jerryt92.j2agent.model.EmbeddingsResponseDto;
import io.github.jerryt92.j2agent.model.Translator;
import io.github.jerryt92.j2agent.server.api.EmbeddingApi;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredRole(UserRoleEnum.ADMIN)
public class EmbeddingsController implements EmbeddingApi {
    @Autowired
    private EmbeddingService embeddingService;

    @Override
    public ResponseEntity<EmbeddingsResponseDto> embed(EmbeddingsRequestDto embeddingsRequestDto) {
        return ResponseEntity.ok(
                Translator.translateToEmbeddingsResponseDto(
                        embeddingService.embed(Translator.translateToEmbeddingsRequest(embeddingsRequestDto))
                )
        );
    }
}
