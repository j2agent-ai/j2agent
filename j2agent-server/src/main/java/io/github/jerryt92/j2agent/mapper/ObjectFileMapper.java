package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ObjectFileMapper {

    List<ObjectFilePo> selectByBucket(String bucket);


    List<ObjectFilePo> selectByBucketAndStatus(
            @Param("bucket") String bucket,
            @Param("status") String status
    );


    ObjectFilePo selectByKey(@Param("bucket") String bucket, @Param("objectKeyHash") String objectKeyHash);


    ObjectFilePo selectById(String id);


    List<ObjectFilePo> selectByObjectKeyPrefix(@Param("bucket") String bucket, @Param("prefix") String prefix);


    int insert(ObjectFilePo po);


    int upsert(ObjectFilePo po);


    int updateStatus(
            @Param("bucket") String bucket,
            @Param("objectKeyHash") String objectKeyHash,
            @Param("status") String status,
            @Param("lastError") String lastError,
            @Param("updatedAt") long updatedAt
    );


    int deleteByKey(@Param("bucket") String bucket, @Param("objectKeyHash") String objectKeyHash);
}
