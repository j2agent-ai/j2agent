package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import com.alibaba.fastjson2.JSON;
import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 将知识库根目录下尚未配置的一级目录补登记为本地文件知识库。
 */
@Slf4j
@Service
public class KnowledgeRepositoryAutoRegistrar {
    private final KnowledgeRepositoryMapper mapper;
    private final KnowledgeRepoProperties properties;

    public KnowledgeRepositoryAutoRegistrar(KnowledgeRepositoryMapper mapper,
                                            KnowledgeRepoProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public void ensureLocalRepositoriesForExistingDirectories() {
        if (StringUtils.isBlank(properties.getRootPath())) {
            return;
        }
        Path rootPath = Path.of(properties.getRootPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootPath)) {
            return;
        }
        try (Stream<Path> stream = Files.list(rootPath)) {
            stream
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(this::isSafeRepoCode)
                    .forEach(this::ensureLocalRepository);
        } catch (IOException e) {
            log.warn("自动发现本地文件知识库失败: rootPath={}, error={}", rootPath, e.getMessage(), e);
        }
    }

    private boolean isSafeRepoCode(String repoCode) {
        if (StringUtils.isBlank(repoCode) || repoCode.startsWith(".")) {
            return false;
        }
        if (".".equals(repoCode) || "..".equals(repoCode) || repoCode.contains("/") || repoCode.contains("\\")) {
            log.warn("跳过自动创建本地文件知识库：目录名不是合法一级目录: {}", repoCode);
            return false;
        }
        return true;
    }

    private void ensureLocalRepository(String repoCode) {
        if (mapper.selectByRepoCode(repoCode) != null) {
            return;
        }
        KnowledgeRepositoryPo po = defaultLocalRepository(repoCode);
        try {
            mapper.insert(po);
            log.info("自动创建本地文件知识库配置: repoCode={}, collection={}",
                    repoCode, KnowledgeRepositoryConstants.defaultCollectionName(repoCode));
        } catch (DuplicateKeyException ignored) {
            log.debug("自动创建本地文件知识库配置跳过：配置已存在: repoCode={}", repoCode);
        }
    }

    private KnowledgeRepositoryPo defaultLocalRepository(String repoCode) {
        long now = System.currentTimeMillis();
        KnowledgeRepositoryPo po = new KnowledgeRepositoryPo();
        po.setId(UUIDv7Utils.randomUUIDv7());
        po.setRepoCode(repoCode);
        po.setType(KnowledgeRepositoryConstants.TYPE_LOCAL_FILE);
        po.setProtocol(null);
        po.setEnabled(true);
        po.setUpdateIntervalMinutes(KnowledgeRepositoryConstants.DEFAULT_UPDATE_INTERVAL_MINUTES);
        po.setStatus(KnowledgeRepositoryConstants.STATUS_IDLE);
        po.setRemoteUrl(null);
        po.setDefaultBranch(null);
        po.setProtocolConfig("{}");
        po.setDisplayName(null);
        po.setMetadataConfig(defaultMetadataConfig(repoCode));
        po.setCredentialConfigCipher(null);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        return po;
    }

    private String defaultMetadataConfig(String repoCode) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("collectionName", KnowledgeRepositoryConstants.defaultCollectionName(repoCode));
        config.put("partitionNames", List.of());
        config.put("minHeadingLevel", KnowledgeRepositoryConstants.DEFAULT_MIN_HEADING_LEVEL);
        config.put("filenameAsTitle", KnowledgeRepositoryConstants.DEFAULT_FILENAME_AS_TITLE);
        return JSON.toJSONString(config);
    }

}
