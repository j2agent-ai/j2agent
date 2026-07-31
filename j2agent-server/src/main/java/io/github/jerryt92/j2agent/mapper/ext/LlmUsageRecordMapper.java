package io.github.jerryt92.j2agent.mapper.ext;

import io.github.jerryt92.j2agent.model.po.LlmUsageRecordPo;
import io.github.jerryt92.j2agent.model.po.LlmUsageRecordQueryRow;
import io.github.jerryt92.j2agent.model.po.LlmUsageSummaryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * llm_usage_record 表写入与审计查询 Mapper。
 * SQL 见 classpath:mapper/ai/ext/LlmUsageRecordMapper.xml
 */
@Mapper
public interface LlmUsageRecordMapper {

    /** 写入一条用量记录。 */
    int insert(LlmUsageRecordPo record);

    /** 统计满足条件的聚合用户行数。 */
    long countSummaryByUser(@Param("userId") String userId,
                            @Param("username") String username,
                            @Param("fromTime") Long fromTime,
                            @Param("toTime") Long toTime);

    /** 按用户聚合 Token 用量（仅 AVAILABLE）。 */
    List<LlmUsageSummaryRow> selectSummaryByUser(@Param("userId") String userId,
                                                 @Param("username") String username,
                                                 @Param("fromTime") Long fromTime,
                                                 @Param("toTime") Long toTime,
                                                 @Param("offset") int offset,
                                                 @Param("limit") int limit);

    /** 全局合计（仅 AVAILABLE，与当前筛选条件一致）。 */
    LlmUsageSummaryRow selectGlobalTotals(@Param("userId") String userId,
                                          @Param("username") String username,
                                          @Param("fromTime") Long fromTime,
                                          @Param("toTime") Long toTime);

    /** 明细条数。 */
    long countRecords(@Param("userId") String userId,
                      @Param("agentId") String agentId,
                      @Param("modelName") String modelName,
                      @Param("callKind") String callKind,
                      @Param("usageStatus") String usageStatus,
                      @Param("fromTime") Long fromTime,
                      @Param("toTime") Long toTime);

    /** 调用明细列表。 */
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
