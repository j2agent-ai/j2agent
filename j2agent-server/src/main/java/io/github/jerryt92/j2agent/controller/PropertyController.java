package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.model.PropertyDto;
import io.github.jerryt92.j2agent.server.api.PropertyApi;
import io.github.jerryt92.j2agent.service.PropertiesService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredRole(UserRoleEnum.ADMIN)
public class PropertyController implements PropertyApi {
    private final PropertiesService propertiesService;

    public PropertyController(PropertiesService propertiesService) {
        this.propertiesService = propertiesService;
    }

    @Override
    public ResponseEntity<Map<String, String>> getProperty(List<String> requestBody) {
        return ResponseEntity.ok(propertiesService.getProperties(requestBody));
    }

    @Override
    public ResponseEntity<Void> putProperty(List<@Valid PropertyDto> propertyDto) {
        propertiesService.putProperty(propertyDto);
        return ResponseEntity.ok().build();
    }
}
