package io.github.jerryt92.j2agent.controller;

import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.server.api.HealthCheckApi;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequiredRole(UserRoleEnum.ADMIN)
public class HealthCheckController implements HealthCheckApi {
    private static final Logger log = LogManager.getLogger(HealthCheckController.class);

    @Override
    public ResponseEntity<Object> checkHealth() {
        return ResponseEntity.ok(JSONObject.of(
                "status", "OK"
        ));
    }

    @PostMapping("/sse")
    public SseEmitter sse(SseEmitter emitter) {
        Thread.startVirtualThread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    emitter.send(SseEmitter.event().data("message - " + i));
                    Thread.sleep(1000);
                }
            } catch (IOException | InterruptedException e) {
                log.error("", e);
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }
}
