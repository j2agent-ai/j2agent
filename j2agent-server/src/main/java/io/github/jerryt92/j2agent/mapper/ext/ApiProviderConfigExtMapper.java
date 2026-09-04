package io.github.jerryt92.j2agent.mapper.ext;

import io.github.jerryt92.j2agent.model.po.mgb.ApiProviderConfigPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * api_provider_config 领域查询与「设为当前」操作（勿写入 mgb 包，避免 MBG 覆盖）。
 */
@Mapper
public interface ApiProviderConfigExtMapper {

    List<ApiProviderConfigPo> selectByApiType(@Param("apiType") String apiType);

    ApiProviderConfigPo selectCurrentByApiType(@Param("apiType") String apiType);

    int clearCurrentByApiType(@Param("apiType") String apiType);

    int markCurrent(@Param("id") String id, @Param("updateTime") Long updateTime);
}
