package io.github.jerryt92.j2agent.utils;

import io.github.jerryt92.j2agent.constants.CommonConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class I18nLocaleUtilsTest {

    @Test
    void resolveRequestLanguageUsesLocaleQueryParamWhenHeadersMissing() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("locale")).thenReturn("en_US");

        assertEquals(CommonConstants.EN_US, I18nLocaleUtils.resolveRequestLanguage(request));
    }

    @Test
    void resolveRequestLanguagePrefersHeaderOverQueryParam() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Locale")).thenReturn("zh_CN");
        when(request.getParameter("locale")).thenReturn("en_US");

        assertEquals(CommonConstants.ZH_CN, I18nLocaleUtils.resolveRequestLanguage(request));
    }

    @Test
    void resolveRequestLanguagePrefersExplicitQueryParamOverBrowserAcceptLanguage() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("locale")).thenReturn("en_US");
        when(request.getHeader("Accept-Language")).thenReturn("zh-CN,zh;q=0.9");

        assertEquals(CommonConstants.EN_US, I18nLocaleUtils.resolveRequestLanguage(request));
    }
}
