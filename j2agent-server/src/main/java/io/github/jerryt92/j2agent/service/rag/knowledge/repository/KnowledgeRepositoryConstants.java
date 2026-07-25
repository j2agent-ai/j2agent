package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

/**
 * 知识库仓库常量定义。
 */
public final class KnowledgeRepositoryConstants {
    public static final String TYPE_LOCAL_FILE = "LOCAL_FILE";
    public static final String TYPE_REMOTE = "REMOTE";
    public static final String PROTOCOL_GIT = "GIT";
    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_SYNCING = "SYNCING";
    public static final String STATUS_SYNCED = "SYNCED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DIRECTORY_MISSING = "DIRECTORY_MISSING";
    public static final int DEFAULT_UPDATE_INTERVAL_MINUTES = 60;
    public static final int DEFAULT_MIN_HEADING_LEVEL = 3;
    public static final boolean DEFAULT_FILENAME_AS_TITLE = true;
    public static final String COLLECTION_PREFIX = "kb_";

    private KnowledgeRepositoryConstants() {
    }

    public static String defaultCollectionName(String repoCode) {
        return COLLECTION_PREFIX + repoCode.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
