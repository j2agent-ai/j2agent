package io.github.jerryt92.j2agent.service.llm.agent.core;

import io.github.jerryt92.j2agent.model.I18nString;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import org.junit.jupiter.api.Test;
import org.springframework.objenesis.SpringObjenesis;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentPluginRegistryResolveAgentIdTest {

    @Test
    void shouldReadAgentIdWithoutCallingConstructor() {
        Object probe = new SpringObjenesis().newInstance(StubPluginAgent.class);
        assertEquals("stub_agent", ((AiAgent) probe).getAgentId());
    }

    static class StubPluginAgent extends AiAgent {
        StubPluginAgent(Object unavailableDependency) {
            throw new IllegalStateException("constructor must not run");
        }

        @Override
        public String getAgentId() {
            return "stub_agent";
        }

        @Override
        public I18nString getAgentName() {
            return new I18nString().zhCN("stub").enUS("stub");
        }

        @Override
        public I18nString getAgentDescription() {
            return new I18nString().zhCN("stub").enUS("stub");
        }

        @Override
        public String loadSystemPrompt() {
            return "stub";
        }
    }
}
