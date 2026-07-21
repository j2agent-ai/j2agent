package io.github.jerryt92.j2agent.service.rag.query;

import io.github.jerryt92.j2agent.logging.rag.QueryTransformAgentRunLog;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentService;
import io.github.jerryt92.j2agent.service.llm.LlmSyncService;
import io.github.jerryt92.j2agent.service.llm.PromptConversationIdExtractor;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.content.Media;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将用户多模态输入（文字 + 图片）归一化为向量/BM25 检索用 query 文本。
 */
@Slf4j
public final class MultimodalQueryTransformer implements QueryTransformer {

    static final int VISION_MAX_TOKENS = 1024;

    private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""
            你是知识库检索助手。根据用户提供的文字与/或图片，生成一条用于向量检索的查询句。
            
            要求：
            1. 仅输出一行简体中文，不超过 200 字
            2. 包含 3-5 个检索关键词
            3. 不要解释、不要前缀
            
            用户文字：{userText}
            """);

    private final LlmSyncService llmSyncService;
    private final ChatAttachmentService chatAttachmentService;
    private final PromptTemplate promptTemplate;
    private final boolean markEnrichedForSkip;

    private MultimodalQueryTransformer(LlmSyncService llmSyncService,
                                       ChatAttachmentService chatAttachmentService,
                                       PromptTemplate promptTemplate,
                                       boolean markEnrichedForSkip) {
        this.llmSyncService = llmSyncService;
        this.chatAttachmentService = chatAttachmentService;
        this.promptTemplate = promptTemplate != null ? promptTemplate : DEFAULT_PROMPT_TEMPLATE;
        this.markEnrichedForSkip = markEnrichedForSkip;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Query transform(Query query) {
        if (query == null) {
            return null;
        }
        UserMessage userMessage = QueryUserMessageSupport.resolveLastUserMessage(query, chatAttachmentService);
        List<Media> media = resolveMedia(userMessage);
        if (media.isEmpty()) {
            log.debug("query transform [multimodal]: no image, passthrough");
            return query;
        }
        String userText = QueryUserMessageSupport.resolveUserText(query, userMessage);
        String userTextPreview = QueryTransformLogSupport.preview(userText);
        log.info("query transform [multimodal]: invoking vision model, userText={}, imageCount={}",
                userTextPreview, media.size());
        QueryTransformAgentRunLog.info(query, "multimodal",
                "action=visionInvoke,userText=" + userTextPreview + ",imageCount=" + media.size(),
                "invoking vision model for query transform");
        String enriched = enrichWithVision(userText, media, PromptConversationIdExtractor.extract(query));
        if (!StringUtils.hasText(enriched)) {
            if (StringUtils.hasText(userText)) {
                log.warn("query transform [multimodal]: vision model returned no retrieval text, fallback to user text");
                QueryTransformAgentRunLog.warn(query, "multimodal", "action=fallbackUserText",
                        "vision model returned no retrieval text, fallback to user text");
                enriched = userText;
            } else {
                log.warn("query transform [multimodal]: vision model failed or empty, skip retrieval for image-only input");
                QueryTransformAgentRunLog.warn(query, "multimodal", "action=skipRetrieval",
                        "vision model failed or empty, skip retrieval for image-only input");
                Map<String, Object> skipContext = new HashMap<>();
                skipContext.put(QueryTransformContextKeys.SKIP_RETRIEVAL, true);
                return QueryUserMessageSupport.withTextAndContext(
                        query, QueryUserMessageSupport.IMAGE_ONLY_QUERY_PLACEHOLDER, skipContext);
            }
        }
        if (!StringUtils.hasText(enriched)) {
            return query;
        }
        Map<String, Object> contextEntries = new HashMap<>();
        if (markEnrichedForSkip) {
            contextEntries.put(QueryTransformContextKeys.MULTIMODAL_ENRICHED, true);
        }
        return QueryUserMessageSupport.withTextAndContext(query, enriched.trim(), contextEntries);
    }

    private List<Media> resolveMedia(UserMessage userMessage) {
        if (userMessage == null) {
            return List.of();
        }
        List<Media> media = userMessage.getMedia();
        if (!CollectionUtils.isEmpty(media)) {
            return media;
        }
        List<ChatAttachmentDto> attachments = ChatMemoryMessageCodec.attachmentsFromUserMessage(userMessage);
        if (attachments.isEmpty() || chatAttachmentService == null) {
            return List.of();
        }
        try {
            return chatAttachmentService.toMedia(attachments);
        } catch (RuntimeException e) {
            log.warn("MultimodalQueryTransformer: failed to convert attachments to media", e);
            return List.of();
        }
    }

    private String enrichWithVision(String userText, List<Media> media, String conversationId) {
        String promptText = promptTemplate.render(Map.of(
                "userText", StringUtils.hasText(userText) ? userText : "（无）"));
        return llmSyncService.callUserMultimodal(promptText, media, VISION_MAX_TOKENS, conversationId);
    }

    public static final class Builder {

        private LlmSyncService llmSyncService;
        private ChatAttachmentService chatAttachmentService;
        private PromptTemplate promptTemplate;
        private boolean markEnrichedForSkip = true;

        public Builder llmSyncService(LlmSyncService llmSyncService) {
            this.llmSyncService = llmSyncService;
            return this;
        }

        public Builder chatAttachmentService(ChatAttachmentService chatAttachmentService) {
            this.chatAttachmentService = chatAttachmentService;
            return this;
        }

        public Builder promptTemplate(PromptTemplate promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        public Builder markEnrichedForSkip(boolean markEnrichedForSkip) {
            this.markEnrichedForSkip = markEnrichedForSkip;
            return this;
        }

        public MultimodalQueryTransformer build() {
            if (llmSyncService == null) {
                throw new IllegalArgumentException("llmSyncService is required");
            }
            return new MultimodalQueryTransformer(
                    llmSyncService,
                    chatAttachmentService,
                    promptTemplate,
                    markEnrichedForSkip);
        }
    }
}
