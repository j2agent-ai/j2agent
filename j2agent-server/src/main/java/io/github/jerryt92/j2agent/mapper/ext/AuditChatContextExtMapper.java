package io.github.jerryt92.j2agent.mapper.ext;

import io.github.jerryt92.j2agent.model.po.mgb.ChatContextRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审计会话列表。
 * SQL 见 classpath:mapper/ai/ext/AuditChatContextExtMapper.xml
 */
@Mapper
public interface AuditChatContextExtMapper {

    /** 统计符合筛选条件的会话条数。 */
    long countContexts(@Param("userId") String userId,
                       @Param("title") String title,
                       @Param("agentId") String agentId,
                       @Param("fromTime") Long fromTime,
                       @Param("toTime") Long toTime);

    /** 分页查询会话；按 context_id、agent_id 倒序。 */
    List<ChatContextRecord> selectContexts(@Param("userId") String userId,
                                           @Param("title") String title,
                                           @Param("agentId") String agentId,
                                           @Param("fromTime") Long fromTime,
                                           @Param("toTime") Long toTime,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);
}
