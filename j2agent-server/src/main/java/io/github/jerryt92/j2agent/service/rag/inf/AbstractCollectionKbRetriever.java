package io.github.jerryt92.j2agent.service.rag.inf;

import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.service.llm.PromptConversationIdExtractor;
import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.rag.RagSourcePublicationService;
import io.github.jerryt92.j2agent.service.rag.RagSourcePathUtils;
import io.github.jerryt92.j2agent.service.rag.query.QueryTransformContextKeys;
import io.github.jerryt92.j2agent.service.rag.retrieval.Retriever;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 与一个或多个 collection 绑定的知识库检索器抽象基类。
 */
@Slf4j
public abstract class AbstractCollectionKbRetriever implements DocumentRetriever {
    private static final String RAG_FALLBACK_TEXT = "知识库向量检索失败，请基于当前对话上下文继续回答，并在不确定时明确说明。";

    private final Retriever retriever;

    protected AbstractCollectionKbRetriever(Retriever retriever) {
        this.retriever = retriever;
    }

    /**
     * 返回当前检索器绑定的 Milvus collection 名称列表。
     * <p>每个 collection 会独立检索，最终按 {@code textChunkId} 去重并保持此列表的顺序。</p>
     */
    protected abstract List<String> boundCollections();

    /**
     * 返回当前检索器绑定的分区列表，默认全分区。
     */
    protected List<String> boundPartitions() {
        return null;
    }

    /**
     * Spring AI 标准检索入口：优先读取 Query 文本，缺失时回退到历史中的最后一条用户消息。
     * <p>生成的 {@link Document} 正文为 MySQL 回填的完整 {@code textChunk}（勿仅用 Milvus 窗口切片或 {@code outline}）。</p>
     */
    @Override
    public List<Document> retrieve(Query query) {
        String conversationId = PromptConversationIdExtractor.extract(query);
        if (query != null
                && query.context() != null
                && Boolean.TRUE.equals(query.context().get(QueryTransformContextKeys.SKIP_RETRIEVAL))) {
            logRagSkip(conversationId, "imageOnlyNoQueryText");
            return Collections.emptyList();
        }
        String queryText = Retriever.resolveQueryText(query);
        if (StringUtils.isBlank(queryText)) {
            logRagSkip(conversationId, "blankQueryText");
            return Collections.emptyList();
        }
        if (isReactToolLoopQuery(query)) {
            logRagSkip(conversationId, "reactToolLoop");
            return Collections.emptyList();
        }
        List<String> collections = normalizeCollections();
        if (collections.isEmpty()) {
            logRagSkip(conversationId, "emptyBoundCollections");
            return Collections.emptyList();
        }

        Map<String, Document> documentsByKey = new LinkedHashMap<>();
        boolean anyFailure = false;
        for (String collection : collections) {
            Retriever.RagChunksResult ragChunksResult = retriever.retrieveRagChunksResult(
                    queryText, collection, boundPartitions(), conversationId);
            if (ragChunksResult.status() == Retriever.RetrievalStatus.SKIPPED_NO_ACCESS) {
                // Retriever 已收口权限：无权限不记“检索完成”
                logRagSkip(conversationId, "noReadableRepositories:" + collection);
                continue;
            }
            if (ragChunksResult.status() == Retriever.RetrievalStatus.FAILED) {
                anyFailure = true;
                logRagRetrieve(conversationId, collection, ragChunksResult.status(),
                        0, 0, ragChunksResult.failureMessage(), List.of());
                continue;
            }
            List<EmbeddingModel.EmbeddingsQueryItem> embeddingsQueryItems = ragChunksResult.items();
            List<Document> collectionDocuments = buildDocuments(embeddingsQueryItems);
            logRagRetrieve(conversationId, collection, ragChunksResult.status(),
                    embeddingsQueryItems.size(), collectionDocuments.size(), null, embeddingsQueryItems);
            for (Document document : collectionDocuments) {
                documentsByKey.putIfAbsent(documentKey(document), document);
            }
        }
        List<Document> documents = new ArrayList<>(documentsByKey.values());
        if (documents.isEmpty() && anyFailure) {
            documents = List.of(buildFallbackDocument());
        }
        String agentId = extractAgentId(query);
        RagSourcePublicationService.tryPublishFromRetriever(conversationId, agentId, documents);
        return documents;
    }

    private List<String> normalizeCollections() {
        List<String> configuredCollections = boundCollections();
        if (configuredCollections == null || configuredCollections.isEmpty()) {
            return List.of();
        }
        return configuredCollections.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private void logRagSkip(String conversationId, String reason) {
        if (conversationId != null) {
            AgentRunLogger.infoByConversationId(conversationId, AgentRunEventType.RAG_SKIP,
                    AgentRunLogger.kv("rag", "collections=" + normalizeCollections() + ",reason=" + reason),
                    "RAG retrieval skipped");
            return;
        }
        log.info("RAG 对话检索跳过: collections={}, reason={}", normalizeCollections(), reason);
    }

    private void logRagRetrieve(String conversationId,
                                String collection,
                                Retriever.RetrievalStatus status,
                                int hits,
                                int docs,
                                String failureMessage,
                                List<EmbeddingModel.EmbeddingsQueryItem> items) {
        String ragSummary = "collection=" + collection
                + ",status=" + status
                + ",hits=" + hits
                + ",docs=" + docs
                + summarizeUniqueSourceFiles(items)
                + (failureMessage == null ? "" : ",reason=" + AgentRunLogger.preview(failureMessage));
        if (conversationId != null) {
            if (status == Retriever.RetrievalStatus.FAILED) {
                AgentRunLogger.warnByConversationId(conversationId, AgentRunEventType.RAG_RETRIEVE,
                        AgentRunLogger.kv("rag", ragSummary),
                        "RAG retrieval degraded");
            } else {
                AgentRunLogger.infoByConversationId(conversationId, AgentRunEventType.RAG_RETRIEVE,
                        AgentRunLogger.kv("rag", ragSummary),
                        "RAG retrieval completed");
            }
            return;
        }
        if (status == Retriever.RetrievalStatus.FAILED) {
            log.warn("RAG 对话检索降级: collection={}, reason={}", collection, failureMessage);
        } else {
            log.info("RAG 对话检索: collection={}, status={}, 分片命中数={}, 生成Document数={}",
                    collection, status, hits, docs);
        }
    }

    /** 从 RAG Query 上下文读取 agentId，缺失时由发布服务从 conversationId 解析。 */
    private static String extractAgentId(Query query) {
        if (query == null || query.context() == null) {
            return null;
        }
        Object raw = query.context().get(AgentRunnableContextKeys.CONTEXT_KEY_AGENT_ID);
        if (raw == null) {
            return null;
        }
        String agentId = raw.toString().trim();
        return agentId.isEmpty() ? null : agentId;
    }

    private List<Document> buildDocuments(List<EmbeddingModel.EmbeddingsQueryItem> embeddingsQueryItems) {
        List<Document> documents = new ArrayList<>();
        int ordinal = 1;
        for (EmbeddingModel.EmbeddingsQueryItem item : embeddingsQueryItems) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("textChunkId", item.getTextChunkId());
            metadata.put("sourceFile", item.getSourceFile());
            metadata.put("chunkOrdinal", ordinal++);
            String documentText = StringUtils.isNotBlank(item.getTextChunk()) ? item.getTextChunk() : item.getText();
            documents.add(Document.builder()
                    .text(documentText)
                    .metadata(metadata)
                    .score((double) item.getScore())
                    .build());
        }
        return documents;
    }

    private String documentKey(Document document) {
        Object textChunkId = document.getMetadata().get("textChunkId");
        if (textChunkId != null && StringUtils.isNotBlank(textChunkId.toString())) {
            return "id:" + textChunkId;
        }
        Object sourceFile = document.getMetadata().get("sourceFile");
        return "doc:" + sourceFile + ":" + document.getText();
    }

    /**
     * 向模型注入检索失败提示，避免 RAG 报错直接打断会话流程。
     */
    private Document buildFallbackDocument() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("ragFallback", "milvus_retrieval_failed");
        metadata.put("sourceFile", "rag-system");
        return Document.builder()
                .text(RAG_FALLBACK_TEXT)
                .metadata(metadata)
                .score(0d)
                .build();
    }

    private static boolean isReactToolLoopQuery(Query query) {
        if (query == null || query.history() == null) {
            return false;
        }
        List<Message> history = query.history();
        int lastUserMessageIndex = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage) {
                lastUserMessageIndex = i;
                break;
            }
        }
        for (int i = lastUserMessageIndex + 1; i < history.size(); i++) {
            Message message = history.get(i);
            if (message instanceof ToolResponseMessage) {
                return true;
            }
            if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
                return true;
            }
        }
        return false;
    }

    /** 统计命中分片中规范化后不重复的知识库文档源文件数。 */
    private static String summarizeUniqueSourceFiles(List<EmbeddingModel.EmbeddingsQueryItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        Set<String> kbPaths = new LinkedHashSet<>();
        for (EmbeddingModel.EmbeddingsQueryItem item : items) {
            if (item == null || StringUtils.isBlank(item.getSourceFile())) {
                continue;
            }
            String normalized = RagSourcePathUtils.normalizeKbSourceRelativePath(item.getSourceFile().trim());
            if (normalized != null) {
                kbPaths.add(normalized);
            }
        }
        if (kbPaths.isEmpty()) {
            return "";
        }
        return ",uniqueSourceFiles=" + kbPaths.size();
    }
}
