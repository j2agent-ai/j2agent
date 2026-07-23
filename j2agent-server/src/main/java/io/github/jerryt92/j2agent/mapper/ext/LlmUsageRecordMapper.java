package io.github.jerryt92.j2agent.mapper.ext;

import io.github.jerryt92.j2agent.model.po.LlmUsageRecordPo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

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

}
