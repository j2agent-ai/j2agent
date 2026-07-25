package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import com.alibaba.fastjson2.JSON;
import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.service.rag.knowledge.repository.KnowledgeRepositoryAutoRegistrar;
import io.github.jerryt92.j2agent.service.rag.knowledge.repository.KnowledgeRepositoryConstants;
import io.github.jerryt92.j2agent.utils.HashUtil;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库目录元数据解析服务，配置来源为知识库列表中的仓库高级配置。
 */
@Service
@DependsOn("flywayInitializer")
public class KnowledgeRepoMetadataService {
    private final KnowledgeRepoProperties properties;
    private final KnowledgeRepositoryMapper repositoryMapper;
    private final KnowledgeRepositoryAutoRegistrar autoRegistrar;
    @Getter
    private volatile Path repoRootPath;
    private volatile Map<String, RepositoryMetadata> metadataByRepoCode = Map.of();

    public KnowledgeRepoMetadataService(KnowledgeRepoProperties properties,
                                        KnowledgeRepositoryMapper repositoryMapper,
                                        KnowledgeRepositoryAutoRegistrar autoRegistrar) {
        this.properties = properties;
        this.repositoryMapper = repositoryMapper;
        this.autoRegistrar = autoRegistrar;
    }

    @PostConstruct
    public void init() {
        if (StringUtils.isBlank(properties.getRootPath())) {
            return;
        }
        repoRootPath = resolveRootPath(properties.getRootPath());
        reloadMetadata();
    }

    /**
     * 重新加载知识库列表中的启用仓库配置。
     */
    public synchronized void reloadMetadata() {
        if (repoRootPath == null) {
            clearMetadata();
            return;
        }
        autoRegistrar.ensureLocalRepositoriesForExistingDirectories();
        Map<String, RepositoryMetadata> next = new LinkedHashMap<>();
        for (KnowledgeRepositoryPo po : repositoryMapper.selectEnabledAll()) {
            MetadataConfig metadataConfig = parseMetadataConfig(po.getMetadataConfig());
            if (StringUtils.isBlank(po.getRepoCode()) || StringUtils.isBlank(metadataConfig.collectionName())) {
                continue;
            }
            String repoCode = po.getRepoCode().trim();
            next.put(repoCode, new RepositoryMetadata(
                    repoCode,
                    metadataConfig.collectionName().trim(),
                    metadataConfig.partitionNames(),
                    normalizeMinHeadingLevel(metadataConfig.minHeadingLevel()),
                    normalizeFilenameAsTitle(metadataConfig.filenameAsTitle())
            ));
        }
        metadataByRepoCode = Collections.unmodifiableMap(next);
    }

    public boolean hasMetadata() {
        return !metadataByRepoCode.isEmpty();
    }

    public Set<String> listConfiguredCollectionNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (RepositoryMetadata metadata : metadataByRepoCode.values()) {
            if (StringUtils.isNotBlank(metadata.collectionName())) {
                names.add(metadata.collectionName().trim());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    public List<Path> listConfiguredRepositoryPaths() {
        if (repoRootPath == null || metadataByRepoCode.isEmpty()) {
            return List.of();
        }
        return metadataByRepoCode.keySet().stream()
                .sorted()
                .map(repoCode -> repoRootPath.resolve(repoCode).toAbsolutePath().normalize())
                .toList();
    }

    private void clearMetadata() {
        metadataByRepoCode = Map.of();
    }

    public String resolveCollection(Path filePath) {
        return resolveMetadata(filePath).collectionName();
    }

    public String resolveMetadataConfigHash(Path filePath) {
        return resolveMetadata(filePath).metadataConfigHash();
    }

    /**
     * 根据文件路径解析 Milvus 分区名列表；空列表表示使用默认分区。
     */
    public List<String> resolvePartitionNames(Path filePath) {
        return resolveMetadata(filePath).partitionNames();
    }

    /**
     * 根据文件路径解析 Markdown 分片最小标题级别（1=#，2=##，3=###）。
     */
    public int resolveMinHeadingLevel(Path filePath) {
        return resolveMetadata(filePath).minHeadingLevel();
    }

    /**
     * 根据文件路径解析是否将 Markdown 文件名作为标题链前缀。
     */
    public boolean resolveFilenameAsTitle(Path filePath) {
        return resolveMetadata(filePath).filenameAsTitle();
    }

    private RepositoryMetadata resolveMetadata(Path filePath) {
        if (filePath == null) {
            throw new IllegalStateException("文件路径为空，无法解析知识库配置");
        }
        if (repoRootPath == null) {
            throw new IllegalStateException("知识库根目录未配置，无法解析知识库配置");
        }
        Path absoluteFilePath = filePath.toAbsolutePath().normalize();
        Path absoluteRoot = repoRootPath.toAbsolutePath().normalize();
        if (!absoluteFilePath.startsWith(absoluteRoot)) {
            throw new IllegalStateException("文件不在知识库根目录下: " + absoluteFilePath);
        }
        Path relative = absoluteRoot.relativize(absoluteFilePath);
        if (relative.getNameCount() == 0) {
            throw new IllegalStateException("文件路径未包含知识库仓库目录: " + absoluteFilePath);
        }
        RepositoryMetadata metadata = metadataByRepoCode.get(relative.getName(0).toString());
        if (metadata == null) {
            throw new IllegalStateException("未找到匹配的知识库仓库配置，文件路径: " + absoluteFilePath);
        }
        return metadata;
    }

    private MetadataConfig parseMetadataConfig(String json) {
        if (StringUtils.isBlank(json)) {
            return new MetadataConfig(
                    null,
                    List.of(),
                    KnowledgeRepositoryConstants.DEFAULT_MIN_HEADING_LEVEL,
                    KnowledgeRepositoryConstants.DEFAULT_FILENAME_AS_TITLE);
        }
        Map<String, Object> config = JSON.parseObject(json);
        return new MetadataConfig(
                StringUtils.trimToNull((String) config.get("collectionName")),
                parsePartitionNames(config.get("partitionNames")),
                normalizeMinHeadingLevel(toInteger(config.get("minHeadingLevel"))),
                config.containsKey("filenameAsTitle")
                        ? Boolean.parseBoolean(String.valueOf(config.get("filenameAsTitle")))
                        : KnowledgeRepositoryConstants.DEFAULT_FILENAME_AS_TITLE);
    }

    private List<String> parsePartitionNames(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> parsed = JSON.parseArray(JSON.toJSONString(raw), String.class);
        if (parsed == null || parsed.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String item : parsed) {
            if (StringUtils.isNotBlank(item)) {
                ordered.add(item.trim());
            }
        }
        return List.copyOf(ordered);
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private int normalizeMinHeadingLevel(Integer minHeadingLevel) {
        if (minHeadingLevel == null) {
            return KnowledgeRepositoryConstants.DEFAULT_MIN_HEADING_LEVEL;
        }
        return Math.min(3, Math.max(1, minHeadingLevel));
    }

    private boolean normalizeFilenameAsTitle(Boolean filenameAsTitle) {
        return filenameAsTitle == null
                ? KnowledgeRepositoryConstants.DEFAULT_FILENAME_AS_TITLE
                : filenameAsTitle;
    }

    private Path resolveRootPath(String configuredPath) {
        if (configuredPath.startsWith("classpath:/")) {
            String relativePath = configuredPath.substring("classpath:/".length());
            try {
                return new ClassPathResource(relativePath).getFile().toPath().toRealPath();
            } catch (IOException e) {
                throw new IllegalStateException("无法解析 classpath 知识库目录: " + configuredPath, e);
            }
        }
        Path path = Path.of(configuredPath.trim()).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            return path;
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("无法解析知识库根目录（例如符号链接无效）: " + configuredPath, e);
        }
    }

    private record RepositoryMetadata(
            String repoCode,
            String collectionName,
            List<String> partitionNames,
            int minHeadingLevel,
            boolean filenameAsTitle
    ) {
        private String metadataConfigHash() {
            return HASH_BUILDER.build(this);
        }
    }

    private record MetadataConfig(
            String collectionName,
            List<String> partitionNames,
            Integer minHeadingLevel,
            Boolean filenameAsTitle
    ) {
    }

    private static final MetadataConfigHashBuilder HASH_BUILDER = new MetadataConfigHashBuilder();

    private static final class MetadataConfigHashBuilder {
        private String build(RepositoryMetadata metadata) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("collectionName", metadata.collectionName());
            config.put("partitionNames", metadata.partitionNames());
            config.put("minHeadingLevel", metadata.minHeadingLevel());
            config.put("filenameAsTitle", metadata.filenameAsTitle());
            try {
                return HashUtil.getMessageDigest(
                        JSON.toJSONString(config).getBytes(StandardCharsets.UTF_8),
                        HashUtil.MdAlgorithm.SHA256);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("计算知识库配置指纹失败", e);
            }
        }
    }
}
