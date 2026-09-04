package io.github.jerryt92.j2agent.mapper.ext;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeRepositoryTaskMapper {
    List<Map<String, Object>> selectRecentByRepository(@Param("repositoryId") String repositoryId);
}
