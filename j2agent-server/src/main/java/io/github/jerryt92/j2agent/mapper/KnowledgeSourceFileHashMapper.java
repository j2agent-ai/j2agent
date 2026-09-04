package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.KnowledgeSourceFileHashPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 知识库源文件哈希树持久化 Mapper。
 */
@Mapper
public interface KnowledgeSourceFileHashMapper {
    /**
     * 查询全部文件状态。
     */

    List<KnowledgeSourceFileHashPo> selectAll();

    /**
     * 查询根目录下 ACTIVE 文件与 collection 映射。
     */

    List<Map<String, Object>> selectActiveFileCollectionMap();

    /**
     * 查询入库中断残留文件与 collection 映射。
     */

    List<Map<String, Object>> selectInFlightFileCollectionMap();

    /**
     * 查询根目录下各 collection 的 ACTIVE 文件数量。
     */

    List<Map<String, Object>> selectActiveCollectionCounts();

    /**
     * 写入或更新文件哈希状态。
     */

    int upsert(KnowledgeSourceFileHashPo po);

    /**
     * 标记文件为删除状态。
     */

    int markDeleted(@Param("filePathHash") String filePathHash, @Param("scanTime") long scanTime);

    /**
     * 清空全部哈希记录，供完全重建使用。
     */

    int deleteAll();
}
