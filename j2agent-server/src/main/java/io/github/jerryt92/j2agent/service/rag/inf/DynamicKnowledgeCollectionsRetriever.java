package io.github.jerryt92.j2agent.service.rag.inf;

import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.service.llm.PromptConversationIdExtractor;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.rag.RagSourcePathUtils;
import io.github.jerryt92.j2agent.service.rag.RagSourcePublicationService;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeCollectionSelection;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本轮运行时按前端选择的多个知识库 collection 检索。
 */
@Slf4j
public class DynamicKnowledgeCollectionsRetriever implements DocumentRetriever {
    private static final String RAG_FALLBACK_TEXT = "知识库向量检索失败，请基于当前对话上下文继续回答，并在不确定时明确说明。";

    private final Retriever retriever;

    public DynamicKnowledgeCollectionsRetriever(Retriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public List<Document> retrieve(Query query) {
        String conversationId = PromptConversationIdExtractor.extract(query);
        if (query != null
                && query.context() != null
                && Boolean.TRUE.equals(query.context().get(QueryTransformContextKeys.SKIP_RETRIEVAL))) {
            logRagSkip(conversationId, "imageOnlyNoQueryText");
            return List.of();
        }
        String queryText = Retriever.resolveQueryText(query);
        if (StringUtils.isBlank(queryText)) {
            logRagSkip(conversationId, "blankQueryText");
            return List.of();
        }
        if (isReactToolLoopQuery(query)) {
            logRagSkip(conversationId, "reactToolLoop");
            return List.of();
        }
        List<KnowledgeCollectionSelection.Parsed> selections = extractKnowledgeSelections(query);
        if (selections.isEmpty()) {
            logRagSkip(conversationId, "emptyKnowledgeCollections");
            return List.of();
        }

        Map<String, Document> documentsByKey = new LinkedHashMap<>();
        boolean anyFailure = false;
        for (KnowledgeCollectionSelection.Parsed selection : selections) {
            Retriever.RagChunksResult result =
                    retriever.retrieveRagChunksResult(queryText, selection.collection(), null, conversationId);
            if (result.status() == Retriever.RetrievalStatus.FAILED) {
                anyFailure = true;
                logRagRetrieve(conversationId, selection.rawValue(), result.status(), 0, 0, result.failureMessage(), List.of());
                continue;
            }
            List<EmbeddingModel.EmbeddingsQueryItem> items = result.items().stream()
                    .filter(item -> KnowledgeCollectionSelection.matchesSourceFile(selection, item.getSourceFile()))
                    .toList();
            List<Document> documents = buildDocuments(items);
            logRagRetrieve(conversationId, selection.rawValue(), result.status(), items.size(), documents.size(), null, items);
            for (Document document : documents) {
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

    private List<KnowledgeCollectionSelection.Parsed> extractKnowledgeSelections(Query query) {
        List<KnowledgeCollectionSelection.Parsed> fromContext = normalizeCollectionValue(
                query != null && query.context() != null
                        ? query.context().get(AgentRunnableContextKeys.CONTEXT_KEY_KNOWLEDGE_COLLECTIONS)
                        : null);
        if (!fromContext.isEmpty()) {
            return fromContext;
        }
        if (query == null || query.history() == null) {
            return List.of();
        }
        for (int i = query.history().size() - 1; i >= 0; i--) {
            Message message = query.history().get(i);
            if (message instanceof UserMessage userMessage) {
                List<KnowledgeCollectionSelection.Parsed> fromMetadata = normalizeCollectionValue(
                        userMessage.getMetadata() == null
                                ? null
                                : userMessage.getMetadata().get(
                                        AgentRunnableContextKeys.CONTEXT_KEY_KNOWLEDGE_COLLECTIONS));
                if (!fromMetadata.isEmpty()) {
                    return fromMetadata;
                }
            }
        }
        return List.of();
    }

    private List<KnowledgeCollectionSelection.Parsed> normalizeCollectionValue(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<KnowledgeCollectionSelection.Parsed> normalized = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                addCollectionValue(normalized, item);
            }
        } else {
            addCollectionValue(normalized, raw);
        }
        return normalized;
    }

    private void addCollectionValue(List<KnowledgeCollectionSelection.Parsed> target, Object raw) {
        if (raw == null) {
            return;
        }
        KnowledgeCollectionSelection.Parsed parsed = KnowledgeCollectionSelection.parse(raw.toString());
        if (parsed != null && target.stream().noneMatch(item -> item.rawValue().equals(parsed.rawValue()))) {
            target.add(parsed);
        }
    }

    private List<Document> buildDocuments(List<EmbeddingModel.EmbeddingsQueryItem> items) {
        List<Document> documents = new ArrayList<>();
        int ordinal = 1;
        for (EmbeddingModel.EmbeddingsQueryItem item : items) {
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

    private void logRagSkip(String conversationId, String reason) {
        if (conversationId != null) {
            AgentRunLogger.infoByConversationId(conversationId, AgentRunEventType.RAG_SKIP,
                    AgentRunLogger.kv("rag", "collections=dynamic,reason=" + reason),
                    "RAG retrieval skipped");
            return;
        }
        log.info("动态知识库检索跳过: reason={}", reason);
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
        log.info("动态知识库检索: {}", ragSummary);
    }

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
        return ",uniqueSources=" + kbPaths.size();
    }
}
