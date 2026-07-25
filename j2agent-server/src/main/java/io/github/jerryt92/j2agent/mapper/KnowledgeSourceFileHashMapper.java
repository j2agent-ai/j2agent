package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.KnowledgeSourceFileHashPo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
    @Select("""
            SELECT id, file_path, file_path_hash, file_sha256, metadata_config_hash AS metadataConfigHash, collection_name, partition_names AS partitionNamesJson, knowledge_count, file_size_bytes, last_scan_time, sync_status, created_at, updated_at
            FROM knowledge_source_file_hash
            """)
    List<KnowledgeSourceFileHashPo> selectAll();

    /**
     * 查询根目录下 ACTIVE 文件与 collection 映射。
     */
    @Select("""
            SELECT file_path AS "filePath", collection_name AS "collectionName"
            FROM knowledge_source_file_hash
            WHERE sync_status = 'ACTIVE'
            """)
    List<Map<String, Object>> selectActiveFileCollectionMap();

    /**
     * 查询根目录下各 collection 的 ACTIVE 文件数量。
     */
    @Select("""
            SELECT collection_name AS "collectionName", COUNT(1) AS "fileCount"
            FROM knowledge_source_file_hash
            WHERE sync_status = 'ACTIVE'
            GROUP BY collection_name
            """)
    List<Map<String, Object>> selectActiveCollectionCounts();

    /**
     * 写入或更新文件哈希状态。
     */
    @Insert("""
            INSERT INTO knowledge_source_file_hash
            (id, file_path, file_path_hash, file_sha256, metadata_config_hash, collection_name, partition_names, knowledge_count, file_size_bytes, last_scan_time, sync_status, created_at, updated_at)
            VALUES
            (#{id}, #{filePath}, #{filePathHash}, #{fileSha256}, #{metadataConfigHash}, #{collectionName}, #{partitionNamesJson}, #{knowledgeCount}, #{fileSizeBytes}, #{lastScanTime}, #{syncStatus}, #{createdAt}, #{updatedAt})
            ON CONFLICT (file_path_hash) DO UPDATE SET
              file_sha256 = EXCLUDED.file_sha256,
              metadata_config_hash = EXCLUDED.metadata_config_hash,
              collection_name = EXCLUDED.collection_name,
              partition_names = EXCLUDED.partition_names,
              knowledge_count = EXCLUDED.knowledge_count,
              file_size_bytes = EXCLUDED.file_size_bytes,
              last_scan_time = EXCLUDED.last_scan_time,
              sync_status = EXCLUDED.sync_status,
              updated_at = EXCLUDED.updated_at
            """)
    int upsert(KnowledgeSourceFileHashPo po);

    /**
     * 标记文件为删除状态。
     */
    @Update("""
            UPDATE knowledge_source_file_hash
            SET sync_status = 'DELETED',
                last_scan_time = #{scanTime},
                updated_at = #{scanTime}
            WHERE file_path_hash = #{filePathHash}
            """)
    int markDeleted(@Param("filePathHash") String filePathHash, @Param("scanTime") long scanTime);

    /**
     * 清空全部哈希记录，供完全重建使用。
     */
    @Delete("DELETE FROM knowledge_source_file_hash")
    int deleteAll();
}
