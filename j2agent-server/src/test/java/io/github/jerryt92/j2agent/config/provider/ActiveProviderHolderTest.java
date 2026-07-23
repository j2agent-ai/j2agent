package io.github.jerryt92.j2agent.config.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.mapper.ext.ApiProviderConfigExtMapper;
import io.github.jerryt92.j2agent.model.po.mgb.ApiProviderConfigPo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveProviderHolderTest {

    @Test
    void shouldExposeApiProviderConfigIdOnActiveLlm() {
        ApiProviderConfigExtMapper mapper = mock(ApiProviderConfigExtMapper.class);
        ApiProviderConfigPo po = new ApiProviderConfigPo();
        po.setId("provider-config-1");
        po.setProviderType("anthropic");
        po.setConfigJson("{\"modelName\":\"glm-5.2\"}");
        when(mapper.selectCurrentByApiType(ProviderTypes.API_TYPE_LLM)).thenReturn(po);

        ActiveProviderHolder holder = new ActiveProviderHolder(mapper, new ObjectMapper());
        holder.reloadFromDb();

        assertEquals("provider-config-1", holder.getActiveLlm().getId());
        assertEquals("anthropic", holder.getActiveLlm().getProviderType());
        assertEquals("glm-5.2", holder.getActiveLlm().getModelName());
    }
}
