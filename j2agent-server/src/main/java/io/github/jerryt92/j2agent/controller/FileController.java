package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.service.file.StaticFileService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 文件读取控制器：普通用户可访问已授权的来源文件直链。
 */
@Slf4j
@RestController
@RequiredRole(UserRoleEnum.USER)
public class FileController {
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.jerryt92.j2agent.service.security.ResourceAccessService resourceAccess;

    private final StaticFileService staticFileService;

    public FileController(StaticFileService staticFileService) {
        this.staticFileService = staticFileService;
    }

    /**
     * 读取静态目录文件。
     */
    @GetMapping(CommonConstants.FILE_URL + "static/**")
    public ResponseEntity<Resource> getStaticFile(HttpServletRequest request) {
        String relativePath = extractSubPath(request, CommonConstants.STATIC_FILE_URL);
        if (relativePath == null || relativePath.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        resourceAccess.requireSource(resourceAccess.current(), relativePath);
        Resource resource = staticFileService.getKnowledgeRepoFile(relativePath);
        if (resource == null || !resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return StaticFileService.asFileResponse(resource, Path.of(relativePath).getFileName().toString());
    }

    /**
     * 从知识库仓库直读源文件（图片等资源）。
     */
    @GetMapping(CommonConstants.FILE_URL + "repo/**")
    public ResponseEntity<Resource> getKnowledgeRepoFile(HttpServletRequest request) {
        String relativePath = extractSubPath(request, CommonConstants.REPO_FILE_URL);
        resourceAccess.requireSource(resourceAccess.current(), relativePath);
        if (relativePath == null || relativePath.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = staticFileService.getKnowledgeRepoFile(relativePath);
        if (resource == null || !resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return StaticFileService.asFileResponse(resource, Path.of(relativePath).getFileName().toString());
    }

    /**
     * 从请求 URI 中提取 prefix 之后的相对路径并 URL 解码。
     */
    private String extractSubPath(HttpServletRequest request, String prefix) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (!uri.startsWith(prefix)) {
            return null;
        }
        String subPath = uri.substring(prefix.length());
        return URLDecoder.decode(subPath, StandardCharsets.UTF_8);
    }
}
