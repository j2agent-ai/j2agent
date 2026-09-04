package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.SimpleRagCollectionStatePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SimpleRagCollectionStateMapper {
    SimpleRagCollectionStatePo selectByCollectionName(@Param("collectionName") String collectionName);

    List<String> selectAllCollectionNames();

    int upsert(SimpleRagCollectionStatePo po);

    int deleteByCollectionName(@Param("collectionName") String collectionName);

    /**
     * 按归属 Agent 查询其全部 collection 名称。
     */
    List<String> selectCollectionNamesByOwnerAgentId(@Param("ownerAgentId") String ownerAgentId);

    /**
     * 按归属 Agent 删除其全部同步状态。
     */
    int deleteByOwnerAgentId(@Param("ownerAgentId") String ownerAgentId);
}
