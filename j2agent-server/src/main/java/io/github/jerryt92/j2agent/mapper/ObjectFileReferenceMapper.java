package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.ObjectFileReferencePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ObjectFileReferenceMapper {
    int insertIgnore(ObjectFileReferencePo po);

    int countByFileId(@Param("fileId") String fileId);

    List<String> selectFileIdsByBusinessPrefix(@Param("businessType") String businessType,
                                               @Param("businessIdPrefix") String businessIdPrefix);

    int deleteByBusinessPrefix(@Param("businessType") String businessType,
                               @Param("businessIdPrefix") String businessIdPrefix);

    int deleteByFileId(@Param("fileId") String fileId);
}
