package io.github.jerryt92.j2agent.service.llm.usage;

import io.github.jerryt92.j2agent.config.provider.LlmActiveConfig;
import io.github.jerryt92.j2agent.mapper.ext.LlmUsageRecordMapper;
import io.github.jerryt92.j2agent.model.po.LlmUsageRecordPo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LlmUsageRecorderTest {

    private static final String CONVERSATION_ID = "u1:ctx1:agent1";

    @AfterEach
    void clear() {
        TurnUsageAccumulator.clear(CONVERSATION_ID);
    }

    @Test
    void shouldInsertUsageDetailsWithTurnContextAndCallSequence() {
        LlmUsageRecordMapper usageMapper = mock(LlmUsageRecordMapper.class);
        LlmUsageRecorder recorder = new LlmUsageRecorder(usageMapper);
        TurnUsageAccumulator.bind(new TurnUsageContext("ctx1", "agent1", "turn1", CONVERSATION_ID, "u1"));

        recorder.record(CONVERSATION_ID, "CHAT", config(), available(100));
        recorder.record(CONVERSATION_ID, "CHAT", config(), available(200));

        ArgumentCaptor<LlmUsageRecordPo> captor = ArgumentCaptor.forClass(LlmUsageRecordPo.class);
        verify(usageMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        LlmUsageRecordPo first = captor.getAllValues().get(0);
        LlmUsageRecordPo second = captor.getAllValues().get(1);
        assertEquals("u1", first.getUserId());
        assertEquals("ctx1", first.getContextId());
        assertEquals("agent1", first.getAgentId());
        assertEquals("turn1", first.getTurnId());
        assertEquals("provider-config-1", first.getProviderConfigId());
        assertEquals(1, first.getCallSeq());
        assertEquals(2, second.getCallSeq());
    }

    @Test
    void shouldInsertSyncUsageWithoutConversationContext() {
        LlmUsageRecordMapper usageMapper = mock(LlmUsageRecordMapper.class);
        LlmUsageRecorder recorder = new LlmUsageRecorder(usageMapper);

        recorder.record(null, "SYNC", config(), available(50));

        ArgumentCaptor<LlmUsageRecordPo> captor = ArgumentCaptor.forClass(LlmUsageRecordPo.class);
        verify(usageMapper).insert(captor.capture());
        LlmUsageRecordPo row = captor.getValue();
        assertEquals("SYNC", row.getCallKind());
        assertEquals(1, row.getCallSeq());
        assertEquals(50, row.getBillableTokenCount());
        assertNull(row.getUserId());
        assertNull(row.getContextId());
    }

    private static LlmActiveConfig config() {
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setId("provider-config-1");
        cfg.setProviderType("openai");
        cfg.setModelName("gpt-test");
        return cfg;
    }

    private static LlmUsageSnapshot available(int billable) {
        return LlmUsageSnapshot.builder()
                .usageStatus("AVAILABLE")
                .inputTokens(billable)
                .outputTokens(0)
                .totalTokens(billable)
                .billableTokenCount(billable)
                .build();
    }
}
