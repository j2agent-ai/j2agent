package io.github.jerryt92.j2agent.service.llm.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatResponseDto;
import io.github.jerryt92.j2agent.model.FileDto;
import io.github.jerryt92.j2agent.model.MessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.codec.TypedJsonJacksonCodec;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 resume 轨迹对 MESSAGE/PATCH（来源）与 MESSAGE/DELTA（正文）的不同 payload 策略。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"unchecked", "rawtypes"})
class ChatOutputEventCacheTest {

    private static final String APP = "test-app";
    private static final String CONTEXT_ID = "ctx-1";
    private static final String AGENT_ID = "agent-a";
    private static final String TURN_ID = "turn-1";

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<ChatOutputSnapshot> bucket;

    private ChatOutputEventCache cache;
    private ChatQueueProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ChatQueueProperties();
        properties.setOutputCacheTtlSeconds(300);
        cache = new ChatOutputEventCache(
                redissonClient,
                new RedisKeyNamespaces(APP),
                properties,
                new ObjectMapper());
        when(redissonClient.getBucket(anyString(), any(TypedJsonJacksonCodec.class)))
                .thenReturn((RBucket) bucket);
        when(bucket.get()).thenReturn(null);
    }

    @Test
    void saveStateTrailKeepsMessagePatchPayloadForSrcFileResume() {
        ChatResponseDto patchPayload = new ChatResponseDto();
        MessageDto message = new MessageDto();
        message.setRole(MessageDto.RoleEnum.ASSISTANT);
        message.setSrcFile(java.util.List.of(
                new FileDto().fullFileName("a.md").relativePath("docs/a.md")));
        patchPayload.setMessage(message);

        AgentUiEventEnvelope event = new AgentUiEventEnvelope()
                .setTurnId(TURN_ID)
                .setState(AgentState.THINKING)
                .setPhase(AgentEventPhase.PATCH)
                .setEventType(AgentEventType.MESSAGE)
                .setPayload(patchPayload)
                .setTs(1L);

        cache.saveStateTrailEvent(CONTEXT_ID, AGENT_ID, event);

        ChatOutputSnapshot saved = captureSavedSnapshot();
        assertEquals(1, saved.getStateTrail().size());
        Object retained = saved.getStateTrail().getFirst().getPayload();
        assertInstanceOf(ChatResponseDto.class, retained);
        assertEquals(1, ((ChatResponseDto) retained).getMessage().getSrcFile().size());
        assertEquals("docs/a.md",
                ((ChatResponseDto) retained).getMessage().getSrcFile().getFirst().getRelativePath());
    }

    @Test
    void saveStateTrailNullsMessageDeltaPayloadToAvoidDoubleAppend() {
        ChatResponseDto deltaPayload = new ChatResponseDto();
        MessageDto message = new MessageDto();
        message.setRole(MessageDto.RoleEnum.ASSISTANT);
        message.setContent("hello");
        deltaPayload.setMessage(message);

        AgentUiEventEnvelope event = new AgentUiEventEnvelope()
                .setTurnId(TURN_ID)
                .setState(AgentState.STREAMING_TEXT)
                .setPhase(AgentEventPhase.DELTA)
                .setEventType(AgentEventType.MESSAGE)
                .setPayload(deltaPayload)
                .setTs(1L);

        cache.saveStateTrailEvent(CONTEXT_ID, AGENT_ID, event);

        ChatOutputSnapshot saved = captureSavedSnapshot();
        assertEquals(1, saved.getStateTrail().size());
        assertNull(saved.getStateTrail().getFirst().getPayload());
    }

    private ChatOutputSnapshot captureSavedSnapshot() {
        ArgumentCaptor<ChatOutputSnapshot> captor = ArgumentCaptor.forClass(ChatOutputSnapshot.class);
        verify(bucket).set(captor.capture(), any(Duration.class));
        return captor.getValue();
    }
}
