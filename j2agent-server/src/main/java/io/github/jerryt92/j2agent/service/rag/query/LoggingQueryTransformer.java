package io.github.jerryt92.j2agent.service.rag.query;

import io.github.jerryt92.j2agent.logging.rag.QueryTransformAgentRunLog;
import io.github.jerryt92.j2agent.service.llm.LlmCallContext;
import io.github.jerryt92.j2agent.service.llm.PromptConversationIdExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.util.Objects;

/**
 * 记录 QueryTransformer 单步改写前后的 query 文本。
 */
@Slf4j
public final class LoggingQueryTransformer implements QueryTransformer {

    private final String step;
    private final QueryTransformer delegate;

    public LoggingQueryTransformer(String step, QueryTransformer delegate) {
        this.step = step;
        this.delegate = delegate;
    }

    @Override
    public Query transform(Query query) {
        String before = QueryTransformLogSupport.queryText(query);
        String conversationId = PromptConversationIdExtractor.extract(query);
        Query result = LlmCallContext.withConversationId(conversationId, () -> delegate.transform(query));
        String after = QueryTransformLogSupport.queryText(result);
        if (Objects.equals(before, after)) {
            String preview = QueryTransformLogSupport.preview(after);
            if (isAgentRun(query)) {
                QueryTransformAgentRunLog.info(query, step, "unchanged=" + preview, "query transform unchanged");
            } else {
                log.info("query transform [{}]: unchanged | {}", step, preview);
            }
        } else {
            String beforePreview = QueryTransformLogSupport.preview(before);
            String afterPreview = QueryTransformLogSupport.preview(after);
            if (isAgentRun(query)) {
                QueryTransformAgentRunLog.info(query, step,
                        "before=" + beforePreview + ",after=" + afterPreview,
                        "query transform applied");
            } else {
                log.info("query transform [{}]: {} -> {}", step, beforePreview, afterPreview);
            }
        }
        return result;
    }

    private static boolean isAgentRun(Query query) {
        return PromptConversationIdExtractor.extract(query) != null;
    }
}
