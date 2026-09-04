package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.ObjectStorageSyncDiffPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ObjectStorageSyncDiffMapper {

    int insert(ObjectStorageSyncDiffPo po);


    List<ObjectStorageSyncDiffPo> selectByTask(String taskId);


    ObjectStorageSyncDiffPo selectById(String id);


    int updateResolution(
            @Param("id") String id,
            @Param("status") String status,
            @Param("action") String action,
            @Param("error") String error,
            @Param("updatedAt") long updatedAt
    );


    int deleteByTask(String taskId);


    int deleteByBucketExceptTask(@Param("bucket") String bucket, @Param("taskId") String taskId);


    int deleteByCompletedTaskBefore(@Param("cutoff") long cutoff);
}
