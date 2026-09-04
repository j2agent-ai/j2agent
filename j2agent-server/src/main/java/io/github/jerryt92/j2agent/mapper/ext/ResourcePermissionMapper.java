package io.github.jerryt92.j2agent.mapper.ext;

import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.po.ResourcePermissionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * SQL for loading user resource permissions into the Redis ACL cache.
 */
@Mapper
public interface ResourcePermissionMapper {
    List<ResourcePermissionRow> selectAgentPermissions(@Param("userId") String userId,
                                                       @Param("now") long now);

    List<ResourcePermissionRow> selectKnowledgePermissions(@Param("userId") String userId,
                                                           @Param("now") long now);

    List<Map<String, Object>> listAgentGrants(@Param("agentId") String agentId, @Param("now") long now);

    List<Map<String, Object>> listKnowledgeGrants(@Param("repositoryId") String repositoryId, @Param("now") long now);

    String findOrdinaryUserId(@Param("username") String username);

    Long findAgentExpiry(@Param("userId") String userId, @Param("agentId") String agentId);

    Long findKnowledgeExpiry(@Param("userId") String userId, @Param("repositoryId") String repositoryId);

    int upsertAgentGrant(@Param("id") String id, @Param("userId") String userId, @Param("agentId") String agentId,
                         @Param("permissionLevel") int permissionLevel, @Param("expiresAt") Long expiresAt,
                         @Param("now") long now);

    int upsertKnowledgeGrant(@Param("id") String id, @Param("userId") String userId, @Param("repositoryId") String repositoryId,
                             @Param("permissionLevel") int permissionLevel, @Param("expiresAt") Long expiresAt,
                             @Param("now") long now);

    int deleteAgentGrant(@Param("agentId") String agentId, @Param("userId") String userId);

    int deleteKnowledgeGrant(@Param("repositoryId") String repositoryId, @Param("userId") String userId);

    Boolean agentIsPublic(@Param("agentId") String agentId);

    Boolean repositoryIsPublic(@Param("repositoryId") String repositoryId);

    int updateAgentVisibility(@Param("agentId") String agentId, @Param("isPublic") boolean isPublic, @Param("now") long now);

    int updateRepositoryVisibility(@Param("repositoryId") String repositoryId, @Param("isPublic") boolean isPublic, @Param("now") long now);

    Boolean aclAgentsInitialized();

    int registerAgent(@Param("agentId") String agentId, @Param("isPublic") boolean isPublic, @Param("now") long now);

    int markAclAgentsInitialized();

    List<String> findGrantedUserIds(@Param("repositoryId") String repositoryId);

    int deleteKnowledgeGrantByUser(@Param("repositoryId") String repositoryId, @Param("userId") String userId);

    List<String> selectAgentIds(boolean admin);

    List<KnowledgeRepositoryPo> selectRepositories(@Param("userId") String userId, @Param("manage") boolean manage,
                                                   @Param("elevated") boolean elevated, @Param("ids") java.util.Collection<String> ids);
}
