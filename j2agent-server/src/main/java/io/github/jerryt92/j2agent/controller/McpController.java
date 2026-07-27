package io.github.jerryt92.j2agent.controller;

import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.model.McpStatusItem;
import io.github.jerryt92.j2agent.server.api.McpApi;
import io.github.jerryt92.j2agent.service.llm.mcp.McpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequiredRole(UserRoleEnum.ADMIN)
public class McpController implements McpApi {
    private final McpService mcpService;

    public McpController(McpService mcpService) {
        this.mcpService = mcpService;
    }

    /**
     * 查询当前存储的 MCP JSON 配置。
     */
    @Override
    public ResponseEntity<Object> getMcpConfig() {
        return ResponseEntity.ok(mcpService.getMcpConfig());
    }

    /**
     * 查询 MCP 服务端连接状态及工具清单。
     */
    @Override
    public ResponseEntity<List<McpStatusItem>> getMcpStatus() {
        return ResponseEntity.ok(mcpService.getMcpServerStatus());
    }

    /**
     * 异步更新 MCP JSON 配置，避免阻塞 HTTP 请求线程。
     */
    @Override
    public ResponseEntity<Void> updateMcpConfig(Object body) {
        if (body == null) {
            return ResponseEntity.badRequest().build();
        }
        JSONObject config;
        if (body instanceof JSONObject jsonObject) {
            config = jsonObject;
        } else if (body instanceof Map<?, ?> mapBody) {
            config = new JSONObject(mapBody);
        } else {
            config = JSONObject.parseObject(JSONObject.toJSONString(body));
        }
        Thread.startVirtualThread(() -> {
            try {
                log.info("Async MCP config update task started.");
                mcpService.updateMcpConfig(config);
                log.info("Async MCP config update task finished.");
            } catch (Exception e) {
                log.error("Async MCP config update task failed.", e);
            }
        });
        return ResponseEntity.ok().build();
    }
}

