package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.universal.UniversalAssistantConstants;
import io.github.jerryt92.j2agent.model.I18nString;
import io.github.jerryt92.j2agent.service.rag.inf.DynamicKnowledgeCollectionsRetriever;
import io.github.jerryt92.j2agent.service.rag.retrieval.Retriever;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

/**
 * 平台内置通用知识库问答助手：按前端每轮选择的知识库 collection 做动态 RAG。
 */
@Component
public class KnowledgeQaAssistantAgent extends AiAgent {
    private final DynamicKnowledgeCollectionsRetriever documentRetriever;

    public KnowledgeQaAssistantAgent(Retriever retriever) {
        this.documentRetriever = new DynamicKnowledgeCollectionsRetriever(retriever);
    }

    @Override
    public String getAgentId() {
        return UniversalAssistantConstants.KNOWLEDGE_QA_AGENT_ID;
    }

    @Override
    public I18nString getAgentName() {
        return new I18nString()
                .zhCN(UniversalAssistantConstants.KNOWLEDGE_QA_DISPLAY_NAME)
                .enUS("Knowledge QA Assistant");
    }

    @Override
    public I18nString getAgentDescription() {
        return new I18nString()
                .zhCN("按用户选择的一个或多个知识库进行问答。")
                .enUS("Answer questions using one or more knowledge bases selected by the user.");
    }

    @Override
    public int getSort() {
        return 1;
    }

    @Override
    public String getLogo() {
        return "📚";
    }

    @Override
    protected DocumentRetriever buildDocumentRetriever() {
        return documentRetriever;
    }

    @Override
    public boolean isRagSourceDisplayEnabled() {
        return true;
    }

    @Override
    protected Object[] buildTools() {
        return new Object[0];
    }

    @Override
    public String loadSystemPrompt() {
        return """
                你是 J2Agent AI 平台的通用知识库问答助手。
                1. 你只能基于本轮用户选择的知识库检索结果回答问题。
                2. 如果所选知识库中没有足够依据，请明确说明未在所选知识库中找到相关内容，不要臆造答案。
                3. 回答应简洁、准确，并优先引用检索到的文档事实。
                4. 禁止主动扩大到未选择的知识库范围。
                """;
    }
}
