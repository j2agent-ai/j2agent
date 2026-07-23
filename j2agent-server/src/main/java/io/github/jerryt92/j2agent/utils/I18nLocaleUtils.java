package io.github.jerryt92.j2agent.utils;

import io.github.jerryt92.j2agent.constants.CommonConstants;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求头 / 查询参数解析前端语言标识，并归一化为 zh_CN / en_US。
 */
public final class I18nLocaleUtils {
    private static final String X_LOCALE = "X-Locale";
    private static final String ACCEPT_LANGUAGE = "Accept-Language";

    private I18nLocaleUtils() {
    }

    /**
     * 按 X-Locale → locale/lang 参数 → Accept-Language 优先级解析请求语言。
     */
    public static String resolveRequestLanguage(HttpServletRequest request) {
        if (request == null) {
            return CommonConstants.ZH_CN;
        }
        String locale = request.getHeader(X_LOCALE);
        if (locale == null || locale.isBlank()) {
            locale = request.getParameter("locale");
        }
        if (locale == null || locale.isBlank()) {
            locale = request.getParameter("lang");
        }
        if (locale == null || locale.isBlank()) {
            locale = request.getHeader(ACCEPT_LANGUAGE);
        }
        return normalizeLanguage(locale);
    }

    /**
     * 将任意 locale 字符串归一化为 zh_CN 或 en_US。
     */
    public static String normalizeLanguage(String locale) {
        if (locale == null || locale.isBlank()) {
            return CommonConstants.ZH_CN;
        }
        String normalized = locale.trim().replace('-', '_').toLowerCase();
        if (normalized.startsWith("en")) {
            return CommonConstants.EN_US;
        }
        return CommonConstants.ZH_CN;
    }
}
