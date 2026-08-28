package io.github.jerryt92.j2agent.config.web;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 跨域（CORS）配置，全部由环境变量注入（见 docker/.env）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "j2agent.cors")
public class CorsProperties {

    /** 是否启用 CORS；对应 J2AGENT_CORS_ENABLED */
    private boolean enabled = true;

    /**
     * 允许的 Origin，逗号分隔精确匹配；对应 J2AGENT_CORS_ALLOWED_ORIGINS。
     * 例：https://jerryt92.top,https://www.jerryt92.top
     */
    private String allowedOrigins = "";

    /** 预检缓存秒数；对应 J2AGENT_CORS_MAX_AGE_SECONDS */
    private long maxAgeSeconds = 3600L;

    /** 解析并去空白后的 Origin 列表 */
    public List<String> resolveAllowedOrigins() {
        if (!StringUtils.hasText(allowedOrigins)) {
            return List.of();
        }
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
