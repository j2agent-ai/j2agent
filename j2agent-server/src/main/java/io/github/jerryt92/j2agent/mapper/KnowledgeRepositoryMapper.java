package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库仓库数据访问层。
 */
@Mapper
public interface KnowledgeRepositoryMapper {

    List<KnowledgeRepositoryPo> selectAll();

    List<KnowledgeRepositoryPo> selectRemoteAll();

    List<KnowledgeRepositoryPo> selectEnabledAll();

    KnowledgeRepositoryPo selectById(String id);

    KnowledgeRepositoryPo selectByRepoCode(String repoCode);

    List<KnowledgeRepositoryPo> selectDueRemote(@Param("dueBefore") long dueBefore);

    int insert(KnowledgeRepositoryPo po);

    int updateConfig(KnowledgeRepositoryPo po);

    int updateStatus(@Param("id") String id,
                     @Param("status") String status,
                     @Param("lastError") String lastError,
                     @Param("updatedAt") long updatedAt);

    int updateSyncResult(KnowledgeRepositoryPo po);

    int deleteById(@Param("id") String id);
}
