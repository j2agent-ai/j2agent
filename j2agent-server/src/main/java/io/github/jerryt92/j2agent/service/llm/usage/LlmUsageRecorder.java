package io.github.jerryt92.j2agent.service.llm.usage;

import io.github.jerryt92.j2agent.config.provider.LlmActiveConfig;
import io.github.jerryt92.j2agent.mapper.ext.LlmUsageRecordMapper;
import io.github.jerryt92.j2agent.model.po.LlmUsageRecordPo;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将单次 LLM 调用的 token usage 写入 {@code llm_usage_record}。
 * <p>
 * API Key 用户的 {@code app_user.id} 为 {@code char(32)}，JDBC 读出时可能带尾部空格；
 * 明细表 {@code user_id} 是 varchar，必须 trim 后再落库，否则审计按去空格后的用户 ID 查不到明细。
 */
@Slf4j
@Service
public class LlmUsageRecorder {

    private final LlmUsageRecordMapper usageRecordMapper;

    public LlmUsageRecorder(LlmUsageRecordMapper usageRecordMapper) {
        this.usageRecordMapper = usageRecordMapper;
    }

    /**
     * 记录一次 LLM 调用用量；user/context/agent/turn 标识写入前去掉首尾空白。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void record(String conversationId,
                       String callKind,
                       LlmActiveConfig cfg,
                       LlmUsageSnapshot snapshot) {
        TurnUsageContext context = TurnUsageAccumulator.get(conversationId);
        if (snapshot == null) {
            snapshot = LlmUsageSnapshot.unavailable("usage snapshot is null");
        }
        LlmUsageRecordPo row = new LlmUsageRecordPo();
        row.setId(UUIDv7Utils.randomUUIDv7());
        row.setCallKind(callKind == null ? "CHAT" : callKind);
        row.setProviderConfigId(cfg == null ? null : cfg.getId());
        row.setProviderType(cfg == null ? null : cfg.getProviderType());
        row.setModelName(cfg == null ? null : cfg.getModelName());
        row.setInputTokens(snapshot.getInputTokens());
        row.setOutputTokens(snapshot.getOutputTokens());
        row.setTotalTokens(snapshot.getTotalTokens());
        row.setBillableTokenCount(snapshot.getBillableTokenCount());
        row.setCachedInputTokens(snapshot.getCachedInputTokens());
        row.setCacheReadInputTokens(snapshot.getCacheReadInputTokens());
        row.setCacheCreationInputTokens(snapshot.getCacheCreationInputTokens());
        row.setReasoningOutputTokens(snapshot.getReasoningOutputTokens());
        row.setAudioInputTokens(snapshot.getAudioInputTokens());
        row.setAudioOutputTokens(snapshot.getAudioOutputTokens());
        row.setUsageStatus(snapshot.getUsageStatus());
        row.setNativeUsageJson(snapshot.getNativeUsageJson());
        row.setErrorMessage(snapshot.getErrorMessage());
        row.setCreateTime(System.currentTimeMillis());
        if (context != null) {
            row.setUserId(trimToNull(context.getUserId()));
            row.setContextId(trimToNull(context.getContextId()));
            row.setAgentId(trimToNull(context.getAgentId()));
            row.setTurnId(trimToNull(context.getTurnId()));
            row.setCallSeq(context.nextCallSeq());
        } else if (conversationId != null) {
            ConversationIdCodec.Parts parts = ConversationIdCodec.parse(conversationId);
            row.setUserId(trimToNull(parts.userId()));
            row.setContextId(trimToNull(parts.contextId()));
            row.setAgentId(trimToNull(parts.agentId()));
            row.setCallSeq(1);
        } else {
            row.setCallSeq(1);
        }
        usageRecordMapper.insert(row);
    }

    /** 去掉 CHAR 主键带来的尾部空格，空串视为未归属。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
