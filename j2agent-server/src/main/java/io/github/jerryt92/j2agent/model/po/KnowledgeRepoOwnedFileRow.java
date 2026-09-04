package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

/**
 * 某知识库在分片表与哈希表中登记过的文件行。
 */
@Data
public class KnowledgeRepoOwnedFileRow {
    /** 仓内相对路径（source_file / file_path）。 */
    private String path;
    /** 对应 Milvus collection。 */
    private String collectionName;
}
