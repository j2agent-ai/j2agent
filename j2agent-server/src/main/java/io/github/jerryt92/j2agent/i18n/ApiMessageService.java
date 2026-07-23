package io.github.jerryt92.j2agent.i18n;

import io.github.jerryt92.j2agent.config.i18n.ErrorI18n;
import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.service.security.LoginService;
import io.github.jerryt92.j2agent.utils.I18nLocaleUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

/**
 * 使用 resources/j2agent-i18n*.properties 按前端语言标识解析 API 错误文案。
 */
@Service
public class ApiMessageService {

    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final String MSG_SUFFIX = ".msg";
    private final LoginService loginService;

    public ApiMessageService(LoginService loginService) {
        this.loginService = loginService;
    }

    /**
     * 将错误码解析为当前语言文案；非错误码时返回原文。
     */
    public String resolve(String codeOrText) {
        return resolve(codeOrText, null);
    }

    /**
     * 带占位参数解析错误文案。
     */
    public String resolve(String codeOrText, Object[] args) {
        if (codeOrText == null || !isErrorCode(codeOrText)) {
            return codeOrText;
        }
        String key = codeOrText + MSG_SUFFIX;
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(ErrorI18n.BASENAME, resolveRequestLocale());
            String pattern = bundle.getString(key);
            if (args == null || args.length == 0) {
                return pattern;
            }
            return MessageFormat.format(pattern, args);
        } catch (MissingResourceException ex) {
            return codeOrText;
        }
    }

    private boolean isErrorCode(String text) {
        return ERROR_CODE.matcher(text).matches();
    }

    private Locale resolveRequestLocale() {
        UserContextBo session = loginService.getSession();
        if (session != null && StringUtils.isNotBlank(session.getLanguage())) {
            return toLocale(I18nLocaleUtils.normalizeLanguage(session.getLanguage()));
        }
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String locale = null;
        if (attributes != null) {
            locale = I18nLocaleUtils.resolveRequestLanguage(attributes.getRequest());
        }
        if (locale == null || locale.isBlank()) {
            locale = LocaleContextHolder.getLocale().toLanguageTag();
        }
        return toLocale(I18nLocaleUtils.normalizeLanguage(locale));
    }

    private Locale toLocale(String language) {
        return CommonConstants.EN_US.equals(language) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
    }
}
