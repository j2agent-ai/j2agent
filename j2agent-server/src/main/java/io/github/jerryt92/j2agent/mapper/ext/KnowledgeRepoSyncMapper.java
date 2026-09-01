package io.github.jerryt92.j2agent.mapper.ext;

import io.github.jerryt92.j2agent.model.po.KnowledgeRepoOwnedFileRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 按库同步用到的分片与哈希 SQL。
 * SQL 见 classpath:mapper/ai/ext/KnowledgeRepoSyncMapper.xml
 */
@Mapper
public interface KnowledgeRepoSyncMapper {

    /** 查询本库已入库文件路径与 collection。 */
    List<KnowledgeRepoOwnedFileRow> selectOwnedFilesByRepoCode(@Param("repoCode") String repoCode);

    /** 删除本库逻辑文本块。 */
    int deleteTextChunksByRepoCode(@Param("repoCode") String repoCode);

    /** 删除本库源文件哈希。 */
    int deleteFileHashesByRepoCode(@Param("repoCode") String repoCode);
}
