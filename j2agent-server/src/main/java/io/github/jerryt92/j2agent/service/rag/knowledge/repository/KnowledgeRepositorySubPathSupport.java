package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Git 知识库子路径配置规范化与解析。
 */
public final class KnowledgeRepositorySubPathSupport {
    public static final String SUB_PATHS_KEY = "subPaths";

    private KnowledgeRepositorySubPathSupport() {
    }

    public static List<String> parseProtocolConfigSubPaths(String protocolConfig) {
        if (StringUtils.isBlank(protocolConfig)) {
            return List.of();
        }
        Map<String, Object> config = JSON.parseObject(protocolConfig);
        if (config == null || !config.containsKey(SUB_PATHS_KEY)) {
            return List.of();
        }
        return normalize(JSON.parseArray(JSON.toJSONString(config.get(SUB_PATHS_KEY)), String.class));
    }

    public static String mergeProtocolConfig(String protocolConfig, List<String> subPaths) {
        Map<String, Object> config = StringUtils.isBlank(protocolConfig)
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(JSON.parseObject(protocolConfig));
        List<String> normalized = normalize(subPaths);
        if (normalized.isEmpty()) {
            config.remove(SUB_PATHS_KEY);
        } else {
            config.put(SUB_PATHS_KEY, normalized);
        }
        return JSON.toJSONString(config);
    }

    public static List<String> normalize(List<String> rawSubPaths) {
        if (rawSubPaths == null || rawSubPaths.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : rawSubPaths) {
            String path = normalizeOne(raw);
            if (!normalized.add(path)) {
                throw new IllegalArgumentException("subPaths cannot contain duplicate paths");
            }
        }
        List<String> paths = new ArrayList<>(normalized);
        for (int i = 0; i < paths.size(); i++) {
            for (int j = i + 1; j < paths.size(); j++) {
                String left = paths.get(i);
                String right = paths.get(j);
                if (isNested(left, right) || isNested(right, left)) {
                    throw new IllegalArgumentException("subPaths cannot contain nested paths");
                }
            }
        }
        return paths;
    }

    private static String normalizeOne(String raw) {
        if (StringUtils.isBlank(raw)) {
            throw new IllegalArgumentException("subPaths cannot contain empty paths");
        }
        String path = raw.trim().replace('\\', '/');
        path = StringUtils.strip(path, "/");
        if (StringUtils.isBlank(path) || ".".equals(path) || "..".equals(path)) {
            throw new IllegalArgumentException("subPaths must be repository-relative paths");
        }
        if (raw.trim().startsWith("/") || path.contains("//")) {
            throw new IllegalArgumentException("subPaths must be repository-relative paths");
        }
        for (String segment : path.split("/")) {
            if (StringUtils.isBlank(segment) || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("subPaths must not contain . or .. segments");
            }
        }
        try {
            if (Path.of(path).isAbsolute()) {
                throw new IllegalArgumentException("subPaths must be repository-relative paths");
            }
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("subPaths must be valid paths", e);
        }
        return path;
    }

    private static boolean isNested(String parent, String child) {
        return child.startsWith(parent + "/");
    }
}
