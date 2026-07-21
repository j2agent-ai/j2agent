package io.github.jerryt92.j2agent.service.llm.usage;

import io.github.jerryt92.j2agent.config.provider.LlmActiveConfig;
import io.github.jerryt92.j2agent.mapper.ext.LlmUsageRecordMapper;
import io.github.jerryt92.j2agent.model.po.LlmUsageRecordPo;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class LlmUsageRecorder {

    private final LlmUsageRecordMapper usageRecordMapper;

    public LlmUsageRecorder(LlmUsageRecordMapper usageRecordMapper) {
        this.usageRecordMapper = usageRecordMapper;
    }

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
            row.setUserId(context.getUserId());
            row.setContextId(context.getContextId());
            row.setAgentId(context.getAgentId());
            row.setTurnId(context.getTurnId());
            row.setCallSeq(context.nextCallSeq());
        } else if (conversationId != null) {
            ConversationIdCodec.Parts parts = ConversationIdCodec.parse(conversationId);
            row.setUserId(parts.userId());
            row.setContextId(parts.contextId());
            row.setAgentId(parts.agentId());
            row.setCallSeq(1);
        } else {
            row.setCallSeq(1);
        }
        usageRecordMapper.insert(row);
    }
}
