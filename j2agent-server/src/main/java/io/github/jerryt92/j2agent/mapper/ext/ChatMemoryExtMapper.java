package io.github.jerryt92.j2agent.mapper.ext;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChatMemoryExtMapper {

    @Select("""
            select count(1)
            from chat_context_item
            where context_id = #{contextId}
              and agent_id = #{agentId}
              and message_index = #{messageIndex}
              and chat_role = #{chatRole}
              and content = #{content}
            """)
    int existsByContextAgentIndexRoleContent(@Param("contextId") String contextId,
                                             @Param("agentId") String agentId,
                                             @Param("messageIndex") int messageIndex,
                                             @Param("chatRole") int chatRole,
                                             @Param("content") String content);

    @Insert("""
            insert into chat_context_item
            (message_id, context_id, agent_id, message_index, chat_role, content, feedback, rag_infos, add_time, meta_json)
            values
            (#{messageId}, #{contextId}, #{agentId}, #{messageIndex}, #{chatRole}, #{content}, #{feedback}, #{ragInfos}, #{addTime}, #{metaJson})
            """)
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

    @Select("""
            select COALESCE(last_message_index, -1)
            from chat_context_record
            where context_id = #{contextId}
              and agent_id = #{agentId}
            for update
            """)
    Integer selectLastMessageIndexForUpdate(@Param("contextId") String contextId,
                                            @Param("agentId") String agentId);

    @Update("""
            update chat_context_record
            set last_message_index = #{lastMessageIndex},
                update_time = #{updateTime}
            where context_id = #{contextId}
              and agent_id = #{agentId}
            """)
    int updateRecordCursor(@Param("contextId") String contextId,
                           @Param("agentId") String agentId,
                           @Param("lastMessageIndex") int lastMessageIndex,
                           @Param("updateTime") long updateTime);

    @Update("""
            update chat_context_record
            set title = #{title},
                update_time = #{updateTime}
            where context_id = #{contextId}
              and agent_id = #{agentId}
            """)
    int updateTitle(@Param("contextId") String contextId,
                    @Param("agentId") String agentId,
                    @Param("title") String title,
                    @Param("updateTime") long updateTime);

}
