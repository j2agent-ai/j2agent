package io.github.jerryt92.j2agent.mapper.ext;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatMemoryExtMapper {

    int existsByContextAgentIndexRoleContent(@Param("contextId") String contextId,
                                             @Param("agentId") String agentId,
                                             @Param("messageIndex") int messageIndex,
                                             @Param("chatRole") int chatRole,
                                             @Param("content") String content);

    int insertChatContextItem(@Param("contextId") String contextId,
                              @Param("agentId") String agentId,
                              @Param("messageIndex") int messageIndex,
                              @Param("chatRole") int chatRole,
                              @Param("content") String content,
                              @Param("feedback") Integer feedback,
                              @Param("ragInfos") String ragInfos,
                              @Param("addTime") long addTime,
                              @Param("messageId") String messageId,
                              @Param("metaJson") String metaJson);

    Integer selectLastMessageIndexForUpdate(@Param("contextId") String contextId,
                                            @Param("agentId") String agentId);

    int updateRecordCursor(@Param("contextId") String contextId,
                           @Param("agentId") String agentId,
                           @Param("lastMessageIndex") int lastMessageIndex,
                           @Param("updateTime") long updateTime);

    int updateTitle(@Param("contextId") String contextId,
                    @Param("agentId") String agentId,
                    @Param("title") String title,
                    @Param("updateTime") long updateTime);

}
