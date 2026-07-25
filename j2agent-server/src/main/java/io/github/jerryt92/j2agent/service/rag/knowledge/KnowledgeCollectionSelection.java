package io.github.jerryt92.j2agent.service.rag.knowledge;

import org.apache.commons.lang3.StringUtils;

/**
 * 前端知识库选择值编解码：在不改变底层 Milvus collection 名的前提下携带 repoCode。
 */
public final class KnowledgeCollectionSelection {
    public static final String DELIMITER = "\u0000";

    private KnowledgeCollectionSelection() {
    }

    /**
     * 将 repoCode 与 collection 编码为前端选择值。
     */
    public static String encode(String repoCode, String collection) {
        String normalizedCollection = StringUtils.trimToNull(collection);
        if (normalizedCollection == null) {
            return null;
        }
        String normalizedRepoCode = StringUtils.trimToNull(repoCode);
        return normalizedRepoCode == null
                ? normalizedCollection
                : normalizedRepoCode + DELIMITER + normalizedCollection;
    }

    /**
     * 解析前端选择值；无分隔符时视为纯 collection。
     */
    public static Parsed parse(String raw) {
        String value = StringUtils.trimToNull(raw);
        if (value == null) {
            return null;
        }
        int delimiterIndex = value.indexOf(DELIMITER);
        if (delimiterIndex < 0) {
            return new Parsed(null, value, value);
        }
        String repoCode = StringUtils.trimToNull(value.substring(0, delimiterIndex));
        String collection = StringUtils.trimToNull(value.substring(delimiterIndex + DELIMITER.length()));
        if (collection == null) {
            return null;
        }
        return new Parsed(repoCode, collection, value);
    }

    /**
     * 按选择中的 repoCode 过滤 sourceFile 是否属于该知识库目录。
     */
    public static boolean matchesSourceFile(Parsed selection, String sourceFile) {
        if (selection == null || StringUtils.isBlank(selection.repoCode())) {
            return true;
        }
        String normalizedSourceFile = StringUtils.defaultString(sourceFile).replace('\\', '/');
        String prefix = selection.repoCode() + "/";
        return normalizedSourceFile.equals(selection.repoCode()) || normalizedSourceFile.startsWith(prefix);
    }

    /**
     * 解析后的知识库选择项。
     */
    public record Parsed(String repoCode, String collection, String rawValue) {
    }
}
