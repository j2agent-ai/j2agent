package io.github.jerryt92.j2agent.service.llm.agent.builtin.knowledgeqa;

import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.universal.UniversalAssistantConstants;
import io.github.jerryt92.j2agent.model.I18nString;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeMarkdownImageRewriter;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import io.github.jerryt92.j2agent.service.rag.inf.DynamicKnowledgeCollectionsRetriever;
import io.github.jerryt92.j2agent.service.rag.retrieval.Retriever;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

/**
 * 平台内置通用知识库问答助手：按前端每轮选择的知识库 collection 做动态 RAG。
 */
@Component
public class KnowledgeQaAssistantAgent extends AiAgent {
    private final DynamicKnowledgeCollectionsRetriever documentRetriever;
    private final KnowledgeQaGrepTool grepTool;

    public KnowledgeQaAssistantAgent(Retriever retriever,
                                     KnowledgeRepoMetadataService metadataService,
                                     KnowledgeMarkdownImageRewriter imageRewriter) {
        this.documentRetriever = new DynamicKnowledgeCollectionsRetriever(retriever);
        this.grepTool = new KnowledgeQaGrepTool(metadataService, imageRewriter);
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
        return new Object[]{grepTool};
    }

    @Override
    public String loadSystemPrompt() {
        return """
                你是 J2Agent AI 平台的通用知识库问答助手。
                1. 你只能基于本轮用户选择的知识库检索结果回答问题，禁止主动扩大到未选择的知识库范围。
                2. 收到用户问题后，优先调用 grep_knowledge_repo 检索关键词，同时阅读参考上下文；两侧结果互补时取并集作答。
                3. grep_knowledge_repo 未命中不代表无法回答，必须继续结合参考上下文；若参考上下文含【来源文件】路径且正文不足，可调用 read_knowledge_repo_file 读取完整 Markdown。
                4. 如果所选知识库中没有足够依据，请明确说明未在所选知识库中找到相关内容，不要臆造答案。
                5. 回答应简洁、准确，并优先引用检索到的文档事实；正文含图像 URL 时须按 Markdown 图片格式输出。
                6. 禁止提及答案来源于"grep""向量检索""RAG""知识库"等内部机制，避免"根据上下文""所提供的资料"等套话。
                """;
    }

    @Override
    protected QueryAugmenter buildQueryAugmenter() {
        PromptTemplate promptTemplate = new PromptTemplate("""
                以下为本轮选择知识库的参考上下文（可能与 grep_knowledge_repo 结果互补）。
                
                ---------------------
                {context}
                ---------------------
                
                规则：
                1. 调用 grep_knowledge_repo 检索关键词，同时阅读上述参考上下文；两侧结果取并集作答。
                2. grep_knowledge_repo 有命中时，以原文片段为主、参考上下文补充；未命中时不得放弃作答，必须基于参考上下文回答。
                3. 若参考上下文正文不足且含【来源文件】，可调用 read_knowledge_repo_file 读取完整 Markdown 后再答。
                4. 可换更短关键词再 grep 最多 1 次；之后必须给出答案或说明无法回答，不得编造。
                5. 【正文】中的图像 URL 须按 Markdown 图片格式输出。
                6. 禁止提及答案来源于"grep""向量检索""RAG""知识库"等内部机制，避免"根据上下文""所提供的资料"等套话。
                
                用户问题：{query}
                
                回答：
                """);
        return ContextualQueryAugmenter.builder()
                .promptTemplate(promptTemplate)
                .allowEmptyContext(true)
                .build();
    }
}
