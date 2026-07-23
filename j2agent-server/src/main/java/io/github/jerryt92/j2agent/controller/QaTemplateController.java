package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.model.QaTemplateItem;
import io.github.jerryt92.j2agent.model.QaTemplateList;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.server.api.QaTemplateApi;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.security.LoginService;
import io.github.jerryt92.j2agent.utils.I18nLocaleUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 按 Agent 返回热门问题模板。
 */
@Slf4j
@RestController
public class QaTemplateController implements QaTemplateApi {

    private final AgentRouter agentRouter;
    private final LoginService loginService;

    public QaTemplateController(AgentRouter agentRouter, LoginService loginService) {
        this.agentRouter = agentRouter;
        this.loginService = loginService;
    }

    @Override
    public ResponseEntity<QaTemplateList> getQaTemplate(String agentId, Integer limit) {
        QaTemplateList qaTemplateList = new QaTemplateList();
        try {
            AiAgent agent = agentRouter.route(agentId);
            List<String> selectedTemplates = agent.pickQaTemplateQuestions(resolveLocale(), limit);
            List<QaTemplateItem> data = selectedTemplates.stream()
                    .map(template -> new QaTemplateItem().question(template))
                    .collect(Collectors.toList());
            qaTemplateList.data(data);
            return ResponseEntity.ok(qaTemplateList);
        } catch (Throwable t) {
            log.error("Failed to load qa template for agentId={}", agentId, t);
            qaTemplateList.data(Collections.emptyList());
            return ResponseEntity.ok(qaTemplateList);
        }
    }

    private String resolveLocale() {
        UserContextBo session = loginService.getSession();
        if (session != null && StringUtils.isNotBlank(session.getLanguage())) {
            return I18nLocaleUtils.normalizeLanguage(session.getLanguage());
        }
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return CommonConstants.ZH_CN;
        }
        return I18nLocaleUtils.resolveRequestLanguage(attributes.getRequest());
    }
}
