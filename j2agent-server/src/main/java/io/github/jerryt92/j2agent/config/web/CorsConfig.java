package io.github.jerryt92.j2agent.config.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.CollectionUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 注册 CORS 过滤器，允许外部站点经环境变量白名单直连 /v1 REST。
 */
@Configuration
public class CorsConfig {

    /**
     * 在鉴权拦截器之前处理预检与响应头。
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(CorsProperties corsProperties) {
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(buildCorsFilter(corsProperties));
        registration.addUrlPatterns("/*");
        registration.setName("j2agentCorsFilter");
        // 紧随 TraceIdFilter，保证预检不被 LoginInterceptor 拦成 401/403
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }

    /** 按环境变量白名单构建 CorsFilter；未启用或无 Origin 时不放行 */
    private CorsFilter buildCorsFilter(CorsProperties corsProperties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = corsProperties.resolveAllowedOrigins();
        if (!corsProperties.isEnabled() || CollectionUtils.isEmpty(origins)) {
            return new CorsFilter(source);
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(false);
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of(TraceIdContext.HEADER_NAME));
        config.setMaxAge(corsProperties.getMaxAgeSeconds());

        // REST 与文件接口；WebSocket 另由 allowedOrigin 处理握手
        source.registerCorsConfiguration("/v1/**", config);
        source.registerCorsConfiguration("/file/**", config);
        return new CorsFilter(source);
    }
}
