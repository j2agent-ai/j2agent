package io.github.jerryt92.j2agent.mapper.ext;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface RepositoryMaintenanceMapper {
    int claimSync(@Param("id") String id, @Param("now") long now);

    int insertQueuedTask(@Param("id") String id, @Param("repositoryId") String repositoryId,
                         @Param("repoCode") String repoCode, @Param("userId") String userId, @Param("now") long now);

    int markRunning(@Param("id") String id, @Param("now") long now);

    int markCompleted(@Param("id") String id, @Param("now") long now);

    int markFailed(@Param("id") String id, @Param("message") String message, @Param("now") long now);

    List<Map<String, Object>> findStaleTasks();

    int failQueuedTasks(@Param("repositoryId") String repositoryId, @Param("message") String message, @Param("now") long now);
}
