package io.github.jerryt92.j2agent.config.web;

import io.github.jerryt92.j2agent.constants.ErrorConstants;
import io.github.jerryt92.j2agent.i18n.ApiMessageService;
import io.github.jerryt92.j2agent.service.file.oss.exception.ObjectStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一 API 错误响应，保证前端可读取 message 与 traceId 字段。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ApiMessageService apiMessageService;

    public ApiExceptionHandler(ApiMessageService apiMessageService) {
        this.apiMessageService = apiMessageService;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String message = apiMessageService.resolve(ex.getReason());
        if (message == null) {
            message = ex.getStatusCode().toString();
        }
        return ResponseEntity.status(ex.getStatusCode()).body(errorBody(message));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResourceFound(NoResourceFoundException ex) {
        // 未匹配到控制器的 API 请求也会落入静态资源处理器，不能提示为 UI 缺失。
        boolean apiPath = ex.getResourcePath().matches("/?v\\d+(?:/.*)?");
        String code = apiPath ? ErrorConstants.COMMON_RESOURCE_NOT_FOUND : ErrorConstants.UI_RESOURCE_NOT_FOUND;
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(apiMessageService.resolve(code)));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccess(DataAccessException ex) {
        log.error("data access failed", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(systemErrorBody());
    }

    @ExceptionHandler(ObjectStorageException.class)
    public ResponseEntity<Map<String, String>> handleObjectStorage(ObjectStorageException ex) {
        log.error("object storage failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(systemErrorBody());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(systemErrorBody());
    }

    private Map<String, String> systemErrorBody() {
        return errorBody(apiMessageService.resolve(ErrorConstants.COMMON_INTERNAL_ERROR));
    }

    private static Map<String, String> errorBody(String message) {
        return errorBody(message, TraceIdContext.currentOrNew());
    }

    private static Map<String, String> errorBody(String message, String traceId) {
        Map<String, String> body = new LinkedHashMap<>(2);
        body.put("message", message);
        body.put("traceId", traceId);
        return body;
    }
}
