package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.KnowledgeTextChunkPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 知识库逻辑文本块持久化 Mapper。
 */
@Mapper
public interface KnowledgeTextChunkMapper {

    int upsert(KnowledgeTextChunkPo po);

    List<KnowledgeTextChunkPo> selectByIds(@Param("ids") List<String> ids);

    List<KnowledgeTextChunkPo> selectByIdsInRepositories(@Param("ids") List<String> ids,
                                                         @Param("repos") Collection<String> repos);

    List<KnowledgeTextChunkPo> selectByCollection(@Param("collectionName") String collectionName,
                                                  @Param("search") String search,
                                                  @Param("offset") int offset,
                                                  @Param("limit") int limit);

    List<KnowledgeTextChunkPo> selectByCollectionInRepositories(@Param("collectionName") String collectionName,
                                                                @Param("search") String search,
                                                                @Param("repos") Collection<String> repos,
                                                                @Param("offset") int offset,
                                                                @Param("limit") int limit);

    int deleteBySourceFile(@Param("sourceFile") String sourceFile);

    int deleteAll();
}
