package io.github.jerryt92.j2agent.mapper.ext;

import io.github.jerryt92.j2agent.model.po.LlmUsageRecordPo;
import io.github.jerryt92.j2agent.model.po.LlmUsageRecordQueryRow;
import io.github.jerryt92.j2agent.model.po.LlmUsageSummaryRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * llm_usage_record 表写入与审计查询 Mapper。
 */
@Mapper
public interface LlmUsageRecordMapper {

    @Insert("""
            insert into llm_usage_record
            (id, user_id, context_id, agent_id, turn_id, call_seq, call_kind, provider_config_id,
             provider_type, model_name,
             input_tokens, output_tokens, total_tokens,
             billable_token_count, cached_input_tokens, cache_read_input_tokens, cache_creation_input_tokens,
             reasoning_output_tokens, audio_input_tokens, audio_output_tokens, usage_status, native_usage_json,
             error_message, create_time)
            values
            (#{id}, #{userId}, #{contextId}, #{agentId}, #{turnId}, #{callSeq}, #{callKind}, #{providerConfigId},
             #{providerType}, #{modelName}, #{inputTokens},
             #{outputTokens}, #{totalTokens}, #{billableTokenCount}, #{cachedInputTokens}, #{cacheReadInputTokens},
             #{cacheCreationInputTokens}, #{reasoningOutputTokens}, #{audioInputTokens}, #{audioOutputTokens},
             #{usageStatus}, #{nativeUsageJson}, #{errorMessage}, #{createTime})
            """)
    int insert(LlmUsageRecordPo record);

    /** 统计满足条件的聚合用户行数。 */
    @Select("""
            <script>
            select count(1) from (
              select r.user_id
              from llm_usage_record r
              left join app_user u on u.id = r.user_id
              where r.usage_status = 'AVAILABLE'
              <if test="userId != null and userId != ''">
                and r.user_id = #{userId}
              </if>
              <if test="username != null and username != ''">
                and u.username like concat('%', #{username}, '%')
              </if>
              <if test="fromTime != null">
                and r.create_time &gt;= #{fromTime}
              </if>
              <if test="toTime != null">
                and r.create_time &lt;= #{toTime}
              </if>
              group by r.user_id
            ) t
            </script>
            """)
    long countSummaryByUser(@Param("userId") String userId,
                            @Param("username") String username,
                            @Param("fromTime") Long fromTime,
                            @Param("toTime") Long toTime);

    /** 按用户聚合 Token 用量（仅 AVAILABLE）。 */
    @Select("""
            <script>
            select r.user_id as userId,
                   max(u.username) as username,
                   count(1) as callCount,
                   coalesce(sum(r.input_tokens), 0) as inputTokens,
                   coalesce(sum(r.output_tokens), 0) as outputTokens,
                   coalesce(sum(r.billable_token_count), 0) as billableTokens
            from llm_usage_record r
            left join app_user u on u.id = r.user_id
            where r.usage_status = 'AVAILABLE'
            <if test="userId != null and userId != ''">
              and r.user_id = #{userId}
            </if>
            <if test="username != null and username != ''">
              and u.username like concat('%', #{username}, '%')
            </if>
            <if test="fromTime != null">
              and r.create_time &gt;= #{fromTime}
            </if>
            <if test="toTime != null">
              and r.create_time &lt;= #{toTime}
            </if>
            group by r.user_id
            order by billableTokens desc, callCount desc
            limit #{limit} offset #{offset}
            </script>
            """)
    List<LlmUsageSummaryRow> selectSummaryByUser(@Param("userId") String userId,
                                                 @Param("username") String username,
                                                 @Param("fromTime") Long fromTime,
                                                 @Param("toTime") Long toTime,
                                                 @Param("offset") int offset,
                                                 @Param("limit") int limit);

    /** 全局合计（仅 AVAILABLE，与当前筛选条件一致）。 */
    @Select("""
            <script>
            select cast(null as varchar) as userId,
                   cast(null as varchar) as username,
                   count(1) as callCount,
                   coalesce(sum(r.input_tokens), 0) as inputTokens,
                   coalesce(sum(r.output_tokens), 0) as outputTokens,
                   coalesce(sum(r.billable_token_count), 0) as billableTokens
            from llm_usage_record r
            left join app_user u on u.id = r.user_id
            where r.usage_status = 'AVAILABLE'
            <if test="userId != null and userId != ''">
              and r.user_id = #{userId}
            </if>
            <if test="username != null and username != ''">
              and u.username like concat('%', #{username}, '%')
            </if>
            <if test="fromTime != null">
              and r.create_time &gt;= #{fromTime}
            </if>
            <if test="toTime != null">
              and r.create_time &lt;= #{toTime}
            </if>
            </script>
            """)
    LlmUsageSummaryRow selectGlobalTotals(@Param("userId") String userId,
                                          @Param("username") String username,
                                          @Param("fromTime") Long fromTime,
                                          @Param("toTime") Long toTime);

    /** 明细条数。 */
    @Select("""
            <script>
            select count(1)
            from llm_usage_record r
            left join app_user u on u.id = r.user_id
            where 1 = 1
            <if test="userId != null and userId != ''">
              and r.user_id = #{userId}
            </if>
            <if test="agentId != null and agentId != ''">
              and r.agent_id = #{agentId}
            </if>
            <if test="modelName != null and modelName != ''">
              and r.model_name like concat('%', #{modelName}, '%')
            </if>
            <if test="callKind != null and callKind != ''">
              and r.call_kind = #{callKind}
            </if>
            <if test="usageStatus != null and usageStatus != ''">
              and r.usage_status = #{usageStatus}
            </if>
            <if test="fromTime != null">
              and r.create_time &gt;= #{fromTime}
            </if>
            <if test="toTime != null">
              and r.create_time &lt;= #{toTime}
            </if>
            </script>
            """)
    long countRecords(@Param("userId") String userId,
                      @Param("agentId") String agentId,
                      @Param("modelName") String modelName,
                      @Param("callKind") String callKind,
                      @Param("usageStatus") String usageStatus,
                      @Param("fromTime") Long fromTime,
                      @Param("toTime") Long toTime);

    /** 调用明细列表。 */
    @Select("""
            <script>
            select r.id as id,
                   r.user_id as userId,
                   u.username as username,
                   r.context_id as contextId,
                   r.agent_id as agentId,
                   r.turn_id as turnId,
                   r.call_seq as callSeq,
                   r.call_kind as callKind,
                   r.provider_type as providerType,
                   r.model_name as modelName,
                   r.input_tokens as inputTokens,
                   r.output_tokens as outputTokens,
                   r.total_tokens as totalTokens,
                   r.billable_token_count as billableTokenCount,
                   r.usage_status as usageStatus,
                   r.create_time as createTime
            from llm_usage_record r
            left join app_user u on u.id = r.user_id
            where 1 = 1
            <if test="userId != null and userId != ''">
              and r.user_id = #{userId}
            </if>
            <if test="agentId != null and agentId != ''">
              and r.agent_id = #{agentId}
            </if>
            <if test="modelName != null and modelName != ''">
              and r.model_name like concat('%', #{modelName}, '%')
            </if>
            <if test="callKind != null and callKind != ''">
              and r.call_kind = #{callKind}
            </if>
            <if test="usageStatus != null and usageStatus != ''">
              and r.usage_status = #{usageStatus}
            </if>
            <if test="fromTime != null">
              and r.create_time &gt;= #{fromTime}
            </if>
            <if test="toTime != null">
              and r.create_time &lt;= #{toTime}
            </if>
            order by r.create_time desc, r.call_seq desc
            limit #{limit} offset #{offset}
            </script>
            """)
    List<LlmUsageRecordQueryRow> selectRecords(@Param("userId") String userId,
                                               @Param("agentId") String agentId,
                                               @Param("modelName") String modelName,
                                               @Param("callKind") String callKind,
                                               @Param("usageStatus") String usageStatus,
                                               @Param("fromTime") Long fromTime,
                                               @Param("toTime") Long toTime,
                                               @Param("offset") int offset,
                                               @Param("limit") int limit);

}
