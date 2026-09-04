package io.github.jerryt92.j2agent.service.file;

import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件读取服务（静态 URL 与知识库直链）。
 */
@Slf4j
@Service
public class StaticFileService {
    private static final String REPO_FILE_PATH_MARKER = "/file/repo/";

    private final KnowledgeRepoMetadataService knowledgeRepoMetadataService;

    public StaticFileService(KnowledgeRepoMetadataService knowledgeRepoMetadataService) {
        this.knowledgeRepoMetadataService = knowledgeRepoMetadataService;
    }

    /**
     * 解析静态 URL 路径，仅从知识库仓库查找（兼容历史 /file/static/** 引用）。
     */
    public Resource getStaticFile(String filePath) {
        if (StringUtils.isBlank(filePath)) {
            return null;
        }
        Resource repoResource = loadKnowledgeRepoPath(filePath, false);
        if (repoResource != null) {
            return repoResource;
        }
        Resource searched = searchKnowledgeRepoFile(filePath);
        if (searched != null) {
            log.debug("已从知识库定位静态路径文件: {}", filePath);
            return searched;
        }
        log.warn("静态文件在知识库中未找到: {}", filePath);
        return null;
    }

    /**
     * 从知识库仓库根目录直读源文件。
     */
    public Resource getKnowledgeRepoFile(String relativePath) {
        Resource resource = loadKnowledgeRepoPath(relativePath, true);
        if (resource != null) {
            return resource;
        }
        return null; // No cross-repository fallback for protected references.
    }

    /**
     * 按知识库相对路径加载文件。
     */
    private Resource loadKnowledgeRepoPath(String relativePath, boolean logOnMiss) {
        Path repoRoot = knowledgeRepoMetadataService.getRepoRootPath();
        if (repoRoot == null || StringUtils.isBlank(relativePath)) {
            return null;
        }
        Path normalizedRoot = repoRoot.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relativePath.replace('\\', '/')).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            log.warn("知识库文件路径越界: {}", relativePath);
            return null;
        }
        FileSystemResource resource = new FileSystemResource(resolved);
        if (resource.exists() && resource.isFile()) {
            try {
                Path repository = normalizedRoot.resolve(Path.of(relativePath.replace('\\', '/')).getName(0));
                if (!resolved.toRealPath().startsWith(repository.toRealPath())) return null;
            } catch (IOException e) { return null; }
            return resource;
        }
        if (logOnMiss) {
            log.warn("知识库文件不存在: {}", resolved);
        }
        return null;
    }

    /**
     * 在知识库仓库内按相对路径后缀或文件名搜索文件。
     */
    private Resource searchKnowledgeRepoFile(String filePath) {
        Path repoRoot = knowledgeRepoMetadataService.getRepoRootPath();
        if (repoRoot == null || StringUtils.isBlank(filePath)) {
            return null;
        }
        Path normalizedRoot = repoRoot.toAbsolutePath().normalize();
        if (!Files.exists(normalizedRoot)) {
            return null;
        }
        String normalizedPath = filePath.replace('\\', '/');
        String fileName = Path.of(normalizedPath).getFileName().toString();
        try (Stream<Path> stream = Files.walk(normalizedRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> matchesKnowledgeRepoLookup(normalizedRoot, path, normalizedPath, fileName))
                    .findFirst()
                    .map(FileSystemResource::new)
                    .orElse(null);
        } catch (IOException e) {
            log.warn("搜索知识库文件失败: {}", filePath, e);
            return null;
        }
    }

    /**
     * 判断知识库文件是否匹配查找条件。
     */
    private boolean matchesKnowledgeRepoLookup(Path repoRoot, Path candidate, String normalizedPath, String fileName) {
        String relative = repoRoot.relativize(candidate).toString().replace('\\', '/');
        return relative.equals(normalizedPath)
                || relative.endsWith("/" + normalizedPath)
                || candidate.getFileName().toString().equals(fileName);
    }

    /**
     * 将知识库相对路径编码为 {@link CommonConstants#REPO_FILE_URL} 直链。
     * <p>按路径段分别 {@link URLEncoder#encode}，保留 {@code /}，避免整段编码产生 {@code %2F} 触发 Tomcat 400。</p>
     */
    public static String toRepoFileUrl(String relativePath) {
        if (StringUtils.isBlank(relativePath)) {
            return CommonConstants.REPO_FILE_URL;
        }
        return CommonConstants.REPO_FILE_URL + encodeRepoPathSegments(relativePath);
    }

    /**
     * 对知识库相对路径逐段 URL 编码，段间保留 {@code /}。
     */
    public static String encodeRepoPathSegments(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        return Arrays.stream(normalized.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }

    /**
     * 将历史或外部写入的整段 {@code %2F} 知识库直链改写为按段编码，避免 Tomcat/网关 400。
     * 支持相对路径、{@code /v1/rest/j2agent/...} 与部署网关前缀。
     */
    public static String normalizeRepoFileUrl(String url) {
        if (StringUtils.isBlank(url) || !url.contains("%2F") || !url.contains(REPO_FILE_PATH_MARKER)) {
            return url;
        }
        int markerIdx = url.indexOf(REPO_FILE_PATH_MARKER);
        String prefix = url.substring(0, markerIdx + REPO_FILE_PATH_MARKER.length());
        String remainder = url.substring(markerIdx + REPO_FILE_PATH_MARKER.length());
        int suffixStart = remainder.length();
        for (int i = 0; i < remainder.length(); i++) {
            char ch = remainder.charAt(i);
            if (ch == '?' || ch == '#') {
                suffixStart = i;
                break;
            }
        }
        String encodedTail = remainder.substring(0, suffixStart);
        String suffix = remainder.substring(suffixStart);
        try {
            String relativePath = URLDecoder.decode(encodedTail, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(relativePath)) {
                return url;
            }
            return prefix + encodeRepoPathSegments(relativePath) + suffix;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 从知识库直链 URL 提取仓库内相对路径（已 URL 解码）。
     */
    public static String extractRepoRelativePath(String url) {
        if (StringUtils.isBlank(url) || !url.contains(REPO_FILE_PATH_MARKER)) {
            return null;
        }
        int markerIdx = url.indexOf(REPO_FILE_PATH_MARKER);
        String remainder = url.substring(markerIdx + REPO_FILE_PATH_MARKER.length());
        int suffixStart = remainder.length();
        for (int i = 0; i < remainder.length(); i++) {
            char ch = remainder.charAt(i);
            if (ch == '?' || ch == '#') {
                suffixStart = i;
                break;
            }
        }
        try {
            String relativePath = URLDecoder.decode(remainder.substring(0, suffixStart), StandardCharsets.UTF_8);
            return StringUtils.isBlank(relativePath) ? null : relativePath;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建带正确文件名的文件下载/预览响应（{@code Content-Disposition} + Content-Type）。
     */
    public static ResponseEntity<Resource> asFileResponse(Resource resource, String displayFileName) {
        String fileName = StringUtils.isNotBlank(displayFileName) ? displayFileName : resource.getFilename();
        HttpHeaders headers = new HttpHeaders();
        if (StringUtils.isNotBlank(fileName)) {
            headers.setContentDisposition(ContentDisposition.builder("inline")
                    .filename(fileName, StandardCharsets.UTF_8)
                    .build());
        }
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .headers(headers)
                .contentType(parseMediaType(fileName))
                .body(resource);
    }

    /**
     * 根据文件名后缀解析 HTTP Content-Type。
     */
    public static MediaType parseMediaType(String fullFileName) {
        if (fullFileName == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String fileExtension = fullFileName.substring(fullFileName.lastIndexOf(".") + 1);
        MediaType mediaType;
        switch (fileExtension.toLowerCase()) {
            case "jpg":
            case "jpeg":
                mediaType = MediaType.IMAGE_JPEG;
                break;
            case "png":
                mediaType = MediaType.IMAGE_PNG;
                break;
            case "gif":
                mediaType = MediaType.IMAGE_GIF;
                break;
            case "pdf":
                mediaType = MediaType.APPLICATION_PDF;
                break;
            case "txt":
                mediaType = MediaType.TEXT_PLAIN;
                break;
            case "md":
            case "markdown":
                mediaType = new MediaType("text", "markdown", StandardCharsets.UTF_8);
                break;
            default:
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return mediaType;
    }
}
