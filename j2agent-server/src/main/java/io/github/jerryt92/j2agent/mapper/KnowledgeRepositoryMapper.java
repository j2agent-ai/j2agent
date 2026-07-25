package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 知识库仓库数据访问层。
 */
@Mapper
public interface KnowledgeRepositoryMapper {

    String BASE_COLUMNS = """
            id, repo_code AS repoCode, type, protocol, enabled,
            update_interval_minutes AS updateIntervalMinutes, status,
            remote_url AS remoteUrl, default_branch AS defaultBranch,
            last_revision AS lastRevision, last_revision_message AS lastRevisionMessage,
            last_revision_author AS lastRevisionAuthor, last_revision_time AS lastRevisionTime,
            last_sync_time AS lastSyncTime, last_error AS lastError,
            protocol_config::text AS protocolConfig,
            display_name AS displayName, metadata_config::text AS metadataConfig,
            credential_config_cipher AS credentialConfigCipher,
            created_at AS createdAt, updated_at AS updatedAt
            """;

    @Select("""
            SELECT
            """ + BASE_COLUMNS + """
            FROM knowledge_repository
            ORDER BY created_at DESC
            """)
    List<KnowledgeRepositoryPo> selectAll();

    @Select("""
            SELECT
            """ + BASE_COLUMNS + """
            FROM knowledge_repository
            WHERE type = 'REMOTE'
            ORDER BY created_at DESC
            """)
    List<KnowledgeRepositoryPo> selectRemoteAll();

    @Select("""
            SELECT
            """ + BASE_COLUMNS + """
            FROM knowledge_repository
            WHERE enabled = true
            ORDER BY created_at DESC
            """)
    List<KnowledgeRepositoryPo> selectEnabledAll();

    @Select("""
            SELECT
            """ + BASE_COLUMNS + """
            FROM knowledge_repository
            WHERE id = #{id}
            LIMIT 1
            """)
    KnowledgeRepositoryPo selectById(String id);

    @Select("""
            SELECT
            """ + BASE_COLUMNS + """
            FROM knowledge_repository
            WHERE repo_code = #{repoCode}
            LIMIT 1
            """)
    KnowledgeRepositoryPo selectByRepoCode(String repoCode);

    @Select("""
            SELECT
            """ + BASE_COLUMNS + """
            FROM knowledge_repository
            WHERE enabled = true
              AND type = 'REMOTE'
              AND (last_sync_time IS NULL OR last_sync_time <= #{dueBefore})
            ORDER BY COALESCE(last_sync_time, 0), created_at
            """)
    List<KnowledgeRepositoryPo> selectDueRemote(long dueBefore);

    @Insert("""
            INSERT INTO knowledge_repository
            (id, repo_code, type, protocol, enabled, update_interval_minutes, status,
             remote_url, default_branch, last_revision, last_revision_message, last_revision_author,
             last_revision_time, last_sync_time, last_error, protocol_config,
             display_name, metadata_config, credential_config_cipher,
             created_at, updated_at)
            VALUES
            (#{id}, #{repoCode}, #{type}, #{protocol}, #{enabled}, #{updateIntervalMinutes}, #{status},
             #{remoteUrl}, #{defaultBranch}, #{lastRevision}, #{lastRevisionMessage}, #{lastRevisionAuthor},
             #{lastRevisionTime}, #{lastSyncTime}, #{lastError}, CAST(#{protocolConfig} AS jsonb),
             #{displayName}, CAST(#{metadataConfig} AS jsonb), #{credentialConfigCipher},
             #{createdAt}, #{updatedAt})
            """)
    int insert(KnowledgeRepositoryPo po);

    @Update("""
            UPDATE knowledge_repository
            SET repo_code = #{repoCode},
                type = #{type},
                protocol = #{protocol},
                enabled = #{enabled},
                update_interval_minutes = #{updateIntervalMinutes},
                remote_url = #{remoteUrl},
                default_branch = #{defaultBranch},
                protocol_config = CAST(#{protocolConfig} AS jsonb),
                display_name = #{displayName},
                metadata_config = CAST(#{metadataConfig} AS jsonb),
                credential_config_cipher = #{credentialConfigCipher},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateConfig(KnowledgeRepositoryPo po);

    @Update("""
            UPDATE knowledge_repository
            SET status = #{status},
                last_error = #{lastError},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") String id,
                     @Param("status") String status,
                     @Param("lastError") String lastError,
                     @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE knowledge_repository
            SET status = #{status},
                last_revision = #{lastRevision},
                last_revision_message = #{lastRevisionMessage},
                last_revision_author = #{lastRevisionAuthor},
                last_revision_time = #{lastRevisionTime},
                last_sync_time = #{lastSyncTime},
                last_error = #{lastError},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateSyncResult(KnowledgeRepositoryPo po);

    @Delete("DELETE FROM knowledge_repository WHERE id = #{id}")
    int deleteById(String id);
}
