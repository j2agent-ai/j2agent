package io.github.jerryt92.j2agent.service.llm.agent.builtin.knowledgeqa;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeMarkdownImageRewriter;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repository.KnowledgeRepositoryAutoRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 知识库问答助手工具装配测试。
 */
class KnowledgeQaAssistantAgentTest {

    @Test
    void buildTools_containsDedicatedKnowledgeQaGrepTool() {
        KnowledgeRepositoryMapper mapper = mock(KnowledgeRepositoryMapper.class);
        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        KnowledgeQaAssistantAgent agent = new KnowledgeQaAssistantAgent(
                null,
                new KnowledgeRepoMetadataService(properties, mapper, new KnowledgeRepositoryAutoRegistrar(mapper, properties)),
                new KnowledgeMarkdownImageRewriter());

        Object[] tools = agent.buildTools();

        assertEquals(1, tools.length);
        assertInstanceOf(KnowledgeQaGrepTool.class, tools[0]);
        assertTrue(java.util.Arrays.stream(ToolCallbacks.from(tools))
                .anyMatch(callback -> "grep_knowledge_repo".equals(callback.getToolDefinition().name())));
    }
}
