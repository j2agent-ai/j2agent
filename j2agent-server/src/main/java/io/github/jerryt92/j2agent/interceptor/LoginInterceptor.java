package io.github.jerryt92.j2agent.interceptor;

import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.service.security.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    private final LoginService loginService;

    public LoginInterceptor(LoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS 预检无凭证，直接放行（由 CorsFilter 写响应头）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (loginService.resolveRequest(request)) {
            if (!checkRole(handler)) {
                loginService.clearSession();
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
            return true;
        }
        loginService.clearSession();
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }

    private boolean checkRole(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiredRole requiredRole = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequiredRole.class);
        if (requiredRole == null) {
            requiredRole = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequiredRole.class);
        }
        UserRoleEnum role = requiredRole == null ? UserRoleEnum.USER : requiredRole.value();
        UserContextBo session = loginService.getSession();
        return session != null && session.hasAccess(role);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        loginService.clearSession();
    }
}
