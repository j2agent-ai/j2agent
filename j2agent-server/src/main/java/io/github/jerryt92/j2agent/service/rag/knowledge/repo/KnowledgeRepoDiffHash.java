package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

/**
 * 知识库增量对比指纹：须与 DB 快照、内存缓存使用同一拼接规则。
 */
public final class KnowledgeRepoDiffHash {
    private KnowledgeRepoDiffHash() {
    }

    /**
     * 构造用于增量检测的联合哈希值（含目标 Milvus collection，避免迁库后漏删旧数据）。
     */
    public static String build(String fileSha256, String metadataConfigHash, String collectionName) {
        return (fileSha256 == null ? "" : fileSha256)
                + "|" + (metadataConfigHash == null ? "" : metadataConfigHash)
                + "|" + (collectionName == null ? "" : collectionName);
    }
}
