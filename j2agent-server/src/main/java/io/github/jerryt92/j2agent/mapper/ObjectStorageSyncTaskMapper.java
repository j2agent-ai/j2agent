package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.ObjectStorageSyncTaskPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ObjectStorageSyncTaskMapper {

    int insert(ObjectStorageSyncTaskPo po);


    ObjectStorageSyncTaskPo selectById(String id);


    int countActive(String bucket);


    int markRunning(@Param("id") String id, @Param("startedAt") long startedAt);


    int updateProgress(ObjectStorageSyncTaskPo po);


    int markSuccess(ObjectStorageSyncTaskPo po);


    int markFailed(
            @Param("id") String id,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") long completedAt
    );


    int requestCancellation(@Param("id") String id);


    int markCancelled(@Param("id") String id, @Param("completedAt") long completedAt);


    ObjectStorageSyncTaskPo selectLatestSuccessful(@Param("bucket") String bucket);


    int deleteCompletedBefore(@Param("cutoff") long cutoff);
}
