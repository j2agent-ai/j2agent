package io.github.jerryt92.j2agent.controller;

import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.server.api.AgentGlobalConfigApi;
import io.github.jerryt92.j2agent.service.AgentGlobalConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredRole(UserRoleEnum.ADMIN)
public class AgentGlobalConfigController implements AgentGlobalConfigApi {

    private final AgentGlobalConfigService agentGlobalConfigService;

    public AgentGlobalConfigController(AgentGlobalConfigService agentGlobalConfigService) {
        this.agentGlobalConfigService = agentGlobalConfigService;
    }

    @Override
    public ResponseEntity<Object> getAgentGlobalConfig() {
        return ResponseEntity.ok(agentGlobalConfigService.getConfig());
    }

    @Override
    public ResponseEntity<Void> updateAgentGlobalConfig(Object body) {
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
        agentGlobalConfigService.updateConfig(config);
        return ResponseEntity.ok().build();
    }
}
