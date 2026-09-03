package io.github.jerryt92.j2agent.mapper.ext;

import io.github.jerryt92.j2agent.model.po.ResourcePermissionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SQL for loading user resource permissions into the Redis ACL cache.
 */
@Mapper
public interface ResourcePermissionMapper {
    List<ResourcePermissionRow> selectAgentPermissions(@Param("userId") String userId,
                                                       @Param("now") long now);

    List<ResourcePermissionRow> selectKnowledgePermissions(@Param("userId") String userId,
                                                           @Param("now") long now);
}
