package io.github.jerryt92.j2agent.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextItem;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextRecord;
import io.github.jerryt92.j2agent.service.file.StaticFileService;
import io.github.jerryt92.j2agent.service.llm.ChatContextBo;
import io.github.jerryt92.j2agent.service.llm.TurnStepItem;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import io.github.jerryt92.j2agent.service.llm.reasoning.AssistantMessageReasoningExtractor;
import io.github.jerryt92.j2agent.service.rag.RagSourcePathUtils;
import io.github.jerryt92.j2agent.service.rag.knowledge.bo.KnowledgeVectorBo;
import io.github.jerryt92.j2agent.service.rag.vdb.milvus.MilvusSchemaDefinition;
import io.github.jerryt92.j2agent.utils.HashUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Translator {
    public static EmbeddingModel.EmbeddingsRequest translateToEmbeddingsRequest(EmbeddingsRequestDto requestDto) {
        return new EmbeddingModel.EmbeddingsRequest()
                .setInput(requestDto.getInput());
    }

    public static EmbeddingsResponseDto translateToEmbeddingsResponseDto(EmbeddingModel.EmbeddingsResponse embeddingsResponse) {
        EmbeddingsResponseDto embeddingsResponseDto = new EmbeddingsResponseDto();
        List<EmbeddingsDtoItem> embeddingsDtoItems = new ArrayList<>();
        for (EmbeddingModel.EmbeddingsItem item : embeddingsResponse.getData()) {
            EmbeddingsDtoItem embeddingsItem = new EmbeddingsDtoItem();
            try {
                embeddingsItem.setHash(HashUtil.getMessageDigest(item.getText().getBytes(StandardCharsets.UTF_8), HashUtil.MdAlgorithm.SHA1));
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            embeddingsItem.setEmbeddingModel(item.getEmbeddingModel());
            embeddingsItem.setEmbeddingProvider(item.getEmbeddingProvider());
            embeddingsItem.setText(item.getText());
            List<Float> embeddingsList = new ArrayList<>();
            for (float embedding : item.getEmbeddings()) {
                embeddingsList.add(embedding);
            }
            embeddingsItem.setEmbeddings(embeddingsList);
            embeddingsDtoItems.add(embeddingsItem);
        }
        embeddingsResponseDto.setData(embeddingsDtoItems);
        return embeddingsResponseDto;
    }

    /**
     * 将通用知识向量对象转换为 Milvus 行数据。
     */
    public static JsonObject translateToMilvusData(KnowledgeVectorBo vectorBo) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(MilvusSchemaDefinition.FIELD_SEGMENT_ID, vectorBo.getSegmentId());
        jsonObject.addProperty(MilvusSchemaDefinition.FIELD_TEXT_CHUNK_ID, vectorBo.getTextChunkId());
        jsonObject.addProperty(MilvusSchemaDefinition.FIELD_EMBEDDING_MODEL, vectorBo.getEmbeddingModel());
        jsonObject.addProperty(MilvusSchemaDefinition.FIELD_EMBEDDING_PROVIDER, vectorBo.getEmbeddingProvider());
        jsonObject.addProperty(MilvusSchemaDefinition.FIELD_CHECK_EMBEDDING_HASH, vectorBo.getCheckEmbeddingHash());
        jsonObject.addProperty(MilvusSchemaDefinition.FIELD_TEXT, vectorBo.buildText());
        jsonObject.addProperty(MilvusSchemaDefinition.FIELD_TYPE, vectorBo.getType());
        jsonObject.addProperty(MilvusSchemaDefinition.FIELD_SOURCE_FILE, vectorBo.getSourceFile());
        jsonObject.addProperty(MilvusSchemaDefinition.FIELD_HEADING_PATH, vectorBo.getHeadingPath());
        jsonObject.addProperty(MilvusSchemaDefinition.FIELD_COLLECTION_TAG, vectorBo.getCollectionTag());
        jsonObject.addProperty(MilvusSchemaDefinition.FIELD_FILE_SHA256, vectorBo.getFileSha256());
        JsonArray embeddingsList = new JsonArray();
        for (Float embeddingItem : vectorBo.getEmbedding()) {
            embeddingsList.add(embeddingItem);
        }
        jsonObject.add(MilvusSchemaDefinition.FIELD_EMBEDDING, embeddingsList);
        return jsonObject;
    }

    /**
     * 将向量检索结果转换为返回 DTO（文件直入 Milvus 版本）。
     */
    public static KnowledgeRetrieveItemDto translateToEmbeddingsQueryItemDto(EmbeddingModel.EmbeddingsQueryItem embeddingsQueryItem, boolean isFiltered, KnowledgeRetrieveItemDto.MetricTypeEnum metricType, Integer dimension) {
        KnowledgeRetrieveItemDto knowledgeRetrieveItemDto = new KnowledgeRetrieveItemDto();
        knowledgeRetrieveItemDto.setHash(embeddingsQueryItem.getHash());
        knowledgeRetrieveItemDto.setScore(embeddingsQueryItem.getScore());
        knowledgeRetrieveItemDto.setHybridScore(embeddingsQueryItem.getHybridScore());
        knowledgeRetrieveItemDto.setDenseScore(embeddingsQueryItem.getDenseScore());
        knowledgeRetrieveItemDto.setSparseScore(embeddingsQueryItem.getSparseScore());
        knowledgeRetrieveItemDto.setEmbeddingModel(embeddingsQueryItem.getEmbeddingModel());
        knowledgeRetrieveItemDto.setEmbeddingProvider(embeddingsQueryItem.getEmbeddingProvider());
        knowledgeRetrieveItemDto.setDimension(dimension);
        knowledgeRetrieveItemDto.setOutline(StringUtils.isBlank(embeddingsQueryItem.getHeadingPath())
                ? embeddingsQueryItem.getText()
                : embeddingsQueryItem.getHeadingPath());
        knowledgeRetrieveItemDto.setMetricType(metricType);
        if (metricType != null) {
            knowledgeRetrieveItemDto.setDenseMetricType(KnowledgeRetrieveItemDto.DenseMetricTypeEnum.valueOf(metricType.name()));
        }
        knowledgeRetrieveItemDto.setSparseMetricType(KnowledgeRetrieveItemDto.SparseMetricTypeEnum.BM25);
        knowledgeRetrieveItemDto.setTextChunk(embeddingsQueryItem.getTextChunk());
        knowledgeRetrieveItemDto.setTextChunkId(embeddingsQueryItem.getTextChunkId());
        knowledgeRetrieveItemDto.setSourceFile(embeddingsQueryItem.getSourceFile());
        knowledgeRetrieveItemDto.setIsFiltered(isFiltered);
        return knowledgeRetrieveItemDto;
    }

    public static ChatModelDto.ChatRequest translateToChatRequest(ChatRequestDto request) {
        ChatModelDto.ChatRequest chatRequest = new ChatModelDto.ChatRequest();
        chatRequest.setContextId(request.getContextId());
        List<ChatModelDto.Message> messages = new ArrayList<>();
        for (MessageDto messageDto : request.getMessages()) {
            messages.add(translateToChatMessage(messageDto));
        }
        chatRequest.setMessages(messages);
        return chatRequest;
    }

    public static ChatModelDto.Message translateToChatMessage(MessageDto messageDto) {
        ChatModelDto.Message chatMessage = new ChatModelDto.Message();
        switch (messageDto.getRole()) {
            case SYSTEM:
                chatMessage.setRole(ChatModelDto.Role.SYSTEM);
                break;
            case USER:
                chatMessage.setRole(ChatModelDto.Role.USER);
                break;
            case ASSISTANT:
                chatMessage.setRole(ChatModelDto.Role.ASSISTANT);
                break;
            case TOOL:
                chatMessage.setRole(ChatModelDto.Role.TOOL);
                break;
            default:
                throw new IllegalArgumentException("Invalid role: " + messageDto.getRole());
        }
        chatMessage.setContent(messageDto.getContent());
        chatMessage.setFeedback(messageDto.getFeedback() == null ? ChatModelDto.Feedback.NONE : ChatModelDto.Feedback.fromValue(messageDto.getFeedback().getValue()));
        chatMessage.setToolCalls(null);
        return chatMessage;
    }


    public static ChatRequestDto translateToChatRequestDto(ChatModelDto.ChatRequest request) {
        ChatRequestDto chatRequestDto = new ChatRequestDto();
        chatRequestDto.setContextId(request.getContextId());
        List<MessageDto> messages = new ArrayList<>();
        for (int i = 0; i < request.getMessages().size(); i++) {
            messages.add(translateToChatMessageDto(request.getMessages().get(i), i));
        }
        chatRequestDto.setMessages(messages);
        return chatRequestDto;
    }

    public static ChatModelDto.Message translateToChatMessage(ChatContextItem chatContextItem) {
        ChatModelDto.Message chatMessage = new ChatModelDto.Message();
        switch (chatContextItem.getChatRole()) {
            case 0:
                chatMessage.setRole(ChatModelDto.Role.SYSTEM);
                break;
            case 1:
                chatMessage.setRole(ChatModelDto.Role.USER);
                break;
            case 2:
                chatMessage.setRole(ChatModelDto.Role.ASSISTANT);
                break;
            default:
                throw new IllegalArgumentException("Invalid role: " + chatContextItem.getChatRole());
        }
        chatMessage.setContent(chatContextItem.getContent());
        chatMessage.setFeedback(chatContextItem.getFeedback() == null ? ChatModelDto.Feedback.NONE : ChatModelDto.Feedback.fromValue(chatContextItem.getFeedback()));
        chatMessage.setToolCalls(null);
        chatMessage.setRagInfos(JSONArray.parseArray(chatContextItem.getRagInfos(), RagInfoDto.class));
        return chatMessage;
    }

    public static ChatResponseDto translateToChatResponseDto(ChatClientResponse chatClientResponse, int index) {
        ChatResponseDto chatResponseDto = new ChatResponseDto();
        Generation result = chatClientResponse.chatResponse() == null ? null : chatClientResponse.chatResponse().getResult();
        MessageDto messageDto = new MessageDto();
        messageDto.setRole(MessageDto.RoleEnum.ASSISTANT);
        messageDto.setContent(result == null ? null : result.getOutput().getText());
        if (result != null) {
            String reasoning = AssistantMessageReasoningExtractor.extractFullReasoning(
                    result.getOutput(), result.getMetadata());
            if (StringUtils.isNotBlank(reasoning)) {
                messageDto.setReasoningContent(reasoning);
            }
        }
        messageDto.setIndex(index);
        chatResponseDto.setMessage(messageDto);
        return chatResponseDto;
    }

    public static ChatResponseDto translateToChatResponseDto(ChatModelDto.ChatResponse response, int index) {
        ChatResponseDto chatResponseDto = new ChatResponseDto();
        chatResponseDto.setMessage(translateToChatMessageDto(response.getMessage(), index));
        chatResponseDto.setDone(response.getDone());
        return chatResponseDto;
    }

    public static MessageDto translateToChatMessageDto(ChatModelDto.Message message, int index) {
        if (message == null) {
            return null;
        }
        MessageDto messageDto = new MessageDto();
        messageDto.setIndex(index);
        switch (message.getRole()) {
            case SYSTEM:
                messageDto.setRole(io.github.jerryt92.j2agent.model.MessageDto.RoleEnum.SYSTEM);
                break;
            case USER:
                messageDto.setRole(io.github.jerryt92.j2agent.model.MessageDto.RoleEnum.USER);
                break;
            case ASSISTANT:
                messageDto.setRole(io.github.jerryt92.j2agent.model.MessageDto.RoleEnum.ASSISTANT);
                break;
            case TOOL:
                messageDto.setRole(io.github.jerryt92.j2agent.model.MessageDto.RoleEnum.TOOL);
                break;
            default:
                throw new IllegalArgumentException("Invalid role: " + message.getRole());
        }
        messageDto.setFeedback(io.github.jerryt92.j2agent.model.MessageDto.FeedbackEnum.fromValue(message.getFeedback().getValue()));
        messageDto.setContent(message.getContent());
        if (!CollectionUtils.isEmpty(message.getRagInfos())) {
            List<FileDto> fileDtos = parseSrcFilesFromRagInfosJson(JSON.toJSONString(message.getRagInfos()));
            if (!CollectionUtils.isEmpty(fileDtos)) {
                messageDto.setSrcFile(fileDtos);
            }
        }
        return messageDto;
    }

    public static MessageDto translateToChatMessageDto(ChatContextItem chatContextItem) {
        MessageDto messageDto = new MessageDto();
        messageDto.setIndex(chatContextItem.getMessageIndex());
        messageDto.setDisplayInChat(Boolean.TRUE);
        messageDto.setMessageKind(MessageDto.MessageKindEnum.NORMAL);
        switch (chatContextItem.getChatRole()) {
            case 0:
                messageDto.setRole(io.github.jerryt92.j2agent.model.MessageDto.RoleEnum.SYSTEM);
                break;
            case 1:
                messageDto.setRole(io.github.jerryt92.j2agent.model.MessageDto.RoleEnum.USER);
                break;
            case 2:
                messageDto.setRole(io.github.jerryt92.j2agent.model.MessageDto.RoleEnum.ASSISTANT);
                break;
            case 3:
                messageDto.setRole(io.github.jerryt92.j2agent.model.MessageDto.RoleEnum.TOOL);
                break;
            default:
                throw new IllegalArgumentException("Invalid role: " + chatContextItem.getChatRole());
        }
        if (chatContextItem.getChatRole() == 3) {
            messageDto.setContent(summarizeToolResponseJsonContent(chatContextItem.getContent()));
        } else {
            messageDto.setContent(chatContextItem.getContent());
        }
        messageDto.setFeedback(io.github.jerryt92.j2agent.model.MessageDto.FeedbackEnum.fromValue(chatContextItem.getFeedback()));
        if (StringUtils.isNotEmpty(chatContextItem.getMetaJson())) {
            JSONObject meta = JSON.parseObject(chatContextItem.getMetaJson());
            if (meta != null) {
                if (meta.containsKey("displayInChat")) {
                    messageDto.setDisplayInChat(meta.getBoolean("displayInChat"));
                }
                if (meta.containsKey("kind")) {
                    String k = meta.getString("kind");
                    if ("assistant_tool_calls".equals(k)) {
                        messageDto.setMessageKind(MessageDto.MessageKindEnum.TOOL_ROUND);
                    } else if ("tool_result".equals(k)) {
                        messageDto.setMessageKind(MessageDto.MessageKindEnum.TOOL_RESULT);
                    }
                }
                if (meta.containsKey("reasoningContent")) {
                    String rc = meta.getString("reasoningContent");
                    if (StringUtils.isNotBlank(rc)) {
                        messageDto.setReasoningContent(rc);
                    }
                }
                if (meta.containsKey("attachments")) {
                    List<ChatAttachmentDto> attachments =
                            ChatMemoryMessageCodec.normalizeAttachmentUrls(
                                    meta.getList("attachments", ChatAttachmentDto.class));
                    messageDto.setAttachments(attachments);
                }
                if (meta.containsKey(ChatMemoryMessageCodec.META_PENDING_QUESTION)) {
                    applyPendingQuestionFromJson(meta.getString(ChatMemoryMessageCodec.META_PENDING_QUESTION),
                            messageDto);
                }
            }
        }
        if (chatContextItem.getChatRole() != null && chatContextItem.getChatRole() == 2) {
            String raw = chatContextItem.getContent();
            boolean hasPendingQuestion = messageDto.getPendingQuestion() != null;
            // turn_trace 审计行：单条翻译时也隐藏，避免无 meta 的历史行泄漏到列表
            if (isTurnTracePersistenceJson(raw)) {
                messageDto.setDisplayInChat(Boolean.FALSE);
                messageDto.setMessageKind(MessageDto.MessageKindEnum.TOOL_ROUND);
                messageDto.setContent("");
            } else if (isAssistantToolPersistenceJson(raw)) {
                messageDto.setDisplayInChat(Boolean.FALSE);
                messageDto.setMessageKind(MessageDto.MessageKindEnum.TOOL_ROUND);
                messageDto.setContent("");
            } else if (!StringUtils.isNotBlank(raw) && !hasPendingQuestion) {
                messageDto.setDisplayInChat(Boolean.FALSE);
                messageDto.setMessageKind(MessageDto.MessageKindEnum.TOOL_ROUND);
                messageDto.setContent("");
            }
        }
        applySrcFileFromRagInfosJson(chatContextItem.getRagInfos(), messageDto);
        if (MessageDto.MessageKindEnum.TOOL_ROUND.equals(messageDto.getMessageKind())) {
            messageDto.setContent("");
        }
        return messageDto;
    }

    public static HistoryContextItem translateToHistoryContextItem(ChatContextRecord chatContextRecord) {
        HistoryContextItem historyContextItem = new HistoryContextItem();
        historyContextItem.setContextId(chatContextRecord.getContextId());
        historyContextItem.setAgentId(chatContextRecord.getAgentId());
        // 用户态历史列表不回传 userId，避免与审计接口混淆；跨用户查询走 AuditContextItemDto
        historyContextItem.setTitle(chatContextRecord.getTitle());
        historyContextItem.setLastUpdateTime(chatContextRecord.getUpdateTime());
        return historyContextItem;
    }

    public static ChatContextDto translateToChatContextDto(ChatContextBo chatContextBo) {
        return translateToChatContextDto(chatContextBo, true);
    }

    /**
     * @param ragSourceDisplayEnabled 为 {@code false} 时不向 DTO 填充 {@code srcFile}（库表 rag_infos 仍保留）
     */
    public static ChatContextDto translateToChatContextDto(ChatContextBo chatContextBo,
                                                           boolean ragSourceDisplayEnabled) {
        ChatContextDto chatContextDto = new ChatContextDto();
        chatContextDto.setContextId(chatContextBo.getContextId());
        chatContextDto.setAgentId(chatContextBo.getAgentId());
        List<MessageDto> messages = new ArrayList<>();
        int lastVisibleAssistantIndex = -1;
        for (int i = 0; i < chatContextBo.getMessages().size(); i++) {
            Message message = chatContextBo.getMessages().get(i);
            if (message instanceof AssistantMessage am) {
                String assistantText = am.getText() != null ? am.getText() : "";
                if (isTurnTracePersistenceJson(assistantText)) {
                    List<TurnStepDto> steps = toTurnStepDtos(parseTurnTraceSteps(assistantText));
                    if (lastVisibleAssistantIndex >= 0 && !steps.isEmpty()) {
                        messages.get(lastVisibleAssistantIndex).setTurnSteps(steps);
                    }
                    continue;
                }
            }
            MessageDto messageDto = new MessageDto();
            messageDto.setIndex(messages.size());
            messageDto.setDisplayInChat(Boolean.TRUE);
            messageDto.setMessageKind(MessageDto.MessageKindEnum.NORMAL);
            messageDto.setFeedback(MessageDto.FeedbackEnum.NONE);
            if (message instanceof UserMessage um) {
                messageDto.setRole(MessageDto.RoleEnum.USER);
                messageDto.setContent(message.getText());
                List<ChatAttachmentDto> attachments = ChatMemoryMessageCodec.attachmentsFromUserMessage(um);
                if (!CollectionUtils.isEmpty(attachments)) {
                    messageDto.setAttachments(attachments);
                }
            } else if (message instanceof AssistantMessage am) {
                messageDto.setRole(MessageDto.RoleEnum.ASSISTANT);
                String assistantText = am.getText() != null ? am.getText() : "";
                messageDto.setContent(assistantText);
                applyReasoningFromAssistant(am, messageDto);
                applyPendingQuestionFromAssistant(am, messageDto);
                if (ragSourceDisplayEnabled) {
                    applySrcFileFromAssistant(am, messageDto);
                }
                boolean hasReasoning = StringUtils.isNotBlank(messageDto.getReasoningContent());
                boolean hasSrcFile = ragSourceDisplayEnabled
                        && !CollectionUtils.isEmpty(messageDto.getSrcFile());
                boolean hasPendingQuestion = messageDto.getPendingQuestion() != null;
                // 含 tool_calls、持久化 JSON 形态、技能加载审计行、或历史空正文脏数据：均不展示空气泡（有来源/提问时仍展示）
                boolean hideToolRound = am.hasToolCalls()
                        || isAssistantToolPersistenceJson(assistantText)
                        || isSkillLoadAuditPersistenceJson(assistantText)
                        || (!am.hasToolCalls() && !hasReasoning && !hasSrcFile && !hasPendingQuestion
                        && !StringUtils.isNotBlank(assistantText));
                if (hideToolRound) {
                    messageDto.setDisplayInChat(Boolean.FALSE);
                    messageDto.setMessageKind(MessageDto.MessageKindEnum.TOOL_ROUND);
                    messageDto.setContent("");
                    if (!hasReasoning) {
                        messageDto.setReasoningContent(null);
                    }
                } else {
                    lastVisibleAssistantIndex = messages.size();
                }
            } else if (message instanceof ToolResponseMessage tr) {
                messageDto.setRole(MessageDto.RoleEnum.TOOL);
                messageDto.setContent(summarizeToolResponseForUi(tr));
                messageDto.setDisplayInChat(Boolean.FALSE);
                messageDto.setMessageKind(MessageDto.MessageKindEnum.TOOL_RESULT);
            } else {
                continue;
            }
            messages.add(messageDto);
        }
        chatContextDto.setMessages(messages);
        return chatContextDto;
    }

    private static void applyReasoningFromAssistant(AssistantMessage am, MessageDto messageDto) {
        String reasoning = AssistantMessageReasoningExtractor.extractFullReasoning(am);
        if (StringUtils.isNotBlank(reasoning)) {
            messageDto.setReasoningContent(reasoning);
        }
    }

    private static void applyPendingQuestionFromAssistant(AssistantMessage am, MessageDto messageDto) {
        if (am == null || am.getMetadata() == null) {
            return;
        }
        Object raw = am.getMetadata().get(ChatMemoryMessageCodec.META_PENDING_QUESTION);
        applyPendingQuestionFromJson(raw == null ? null : raw.toString(), messageDto);
    }

    private static void applyPendingQuestionFromJson(String questionJson, MessageDto messageDto) {
        if (!StringUtils.isNotBlank(questionJson)) {
            return;
        }
        AskQuestionDto pendingQuestion = JSON.parseObject(questionJson, AskQuestionDto.class);
        if (pendingQuestion != null) {
            messageDto.setPendingQuestion(pendingQuestion);
        }
    }

    private static void applySrcFileFromAssistant(AssistantMessage am, MessageDto messageDto) {
        if (am == null || am.getMetadata() == null) {
            return;
        }
        Object raw = am.getMetadata().get(ChatMemoryMessageCodec.META_RAG_INFOS);
        applySrcFileFromRagInfosJson(raw == null ? null : raw.toString(), messageDto);
    }

    private static void applySrcFileFromRagInfosJson(String ragInfosJson, MessageDto messageDto) {
        List<FileDto> fileDtos = parseSrcFilesFromRagInfosJson(ragInfosJson);
        if (!CollectionUtils.isEmpty(fileDtos)) {
            messageDto.setSrcFile(fileDtos);
        }
    }

    static List<FileDto> parseSrcFilesFromRagInfosJson(String ragInfosJson) {
        if (StringUtils.isEmpty(ragInfosJson)) {
            return List.of();
        }
        List<RagInfoDto> ragInfoDtos = JSONArray.parseArray(ragInfosJson, RagInfoDto.class);
        if (ragInfoDtos == null || ragInfoDtos.isEmpty()) {
            return List.of();
        }
        Map<String, FileDto> deduped = new LinkedHashMap<>();
        for (RagInfoDto ragInfoDto : ragInfoDtos) {
            if (ragInfoDto == null || ragInfoDto.getSrcFile() == null) {
                continue;
            }
            FileDto srcFile = ragInfoDto.getSrcFile();
            normalizeRepoFileDto(srcFile);
            String dedupeKey = RagSourcePathUtils.sourceDedupeKey(srcFile);
            if (dedupeKey == null) {
                dedupeKey = "idx-" + deduped.size();
            }
            deduped.putIfAbsent(dedupeKey, srcFile);
        }
        return new ArrayList<>(deduped.values());
    }

    private static void normalizeRepoFileDto(FileDto fileDto) {
        if (fileDto == null || StringUtils.isBlank(fileDto.getUrl())) {
            return;
        }
        String normalizedUrl = StaticFileService.normalizeRepoFileUrl(fileDto.getUrl());
        if (!normalizedUrl.equals(fileDto.getUrl())) {
            fileDto.setUrl(normalizedUrl);
        }
        if (StringUtils.isBlank(fileDto.getRelativePath())) {
            String relativePath = StaticFileService.extractRepoRelativePath(normalizedUrl);
            if (StringUtils.isNotBlank(relativePath)) {
                fileDto.setRelativePath(relativePath);
            }
        }
    }

    private static List<TurnStepItem> parseTurnTraceSteps(String text) {
        if (StringUtils.isEmpty(text)) {
            return List.of();
        }
        try {
            JSONObject root = JSON.parseObject(text);
            JSONArray arr = root.getJSONArray("steps");
            if (arr == null || arr.isEmpty()) {
                return List.of();
            }
            List<TurnStepItem> steps = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject n = arr.getJSONObject(i);
                if (n == null) {
                    continue;
                }
                String stateStr = n.getString("state");
                if (StringUtils.isBlank(stateStr)) {
                    continue;
                }
                AgentState state;
                try {
                    state = AgentState.valueOf(stateStr);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String toolName = n.getString("toolName");
                Long ts = n.getLong("ts");
                steps.add(new TurnStepItem(state, toolName, ts));
            }
            return steps;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<TurnStepDto> toTurnStepDtos(List<TurnStepItem> items) {
        if (CollectionUtils.isEmpty(items)) {
            return List.of();
        }
        List<TurnStepDto> dtos = new ArrayList<>();
        for (TurnStepItem item : items) {
            TurnStepDto dto = new TurnStepDto();
            dto.setState(TurnStepDto.StateEnum.fromValue(item.state().name()));
            dto.setToolName(item.toolName());
            dto.setTs(item.ts());
            dtos.add(dto);
        }
        return dtos;
    }

    private static boolean isTurnTracePersistenceJson(String text) {
        if (StringUtils.isEmpty(text)) {
            return false;
        }
        String t = text.trim();
        if (!t.startsWith("{")) {
            return false;
        }
        try {
            JSONObject o = JSON.parseObject(t);
            return ChatMemoryMessageCodec.KIND_TURN_TRACE.equals(o.getString("kind"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断 content 是否为 {@link io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec} 写入的 assistant_tool JSON。
     */
    private static boolean isAssistantToolPersistenceJson(String text) {
        if (StringUtils.isEmpty(text)) {
            return false;
        }
        String t = text.trim();
        if (!t.startsWith("{")) {
            return false;
        }
        try {
            JSONObject o = JSON.parseObject(t);
            return "assistant_tool".equals(o.getString("kind"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断 content 是否为技能加载审计持久化 JSON（与 {@link io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec} 一致）。
     */
    private static boolean isSkillLoadAuditPersistenceJson(String text) {
        if (StringUtils.isEmpty(text)) {
            return false;
        }
        String t = text.trim();
        if (!t.startsWith("{")) {
            return false;
        }
        try {
            JSONObject o = JSON.parseObject(t);
            return io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec.KIND_SKILL_LOAD_AUDIT.equals(
                    o.getString("kind"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从持久化 JSON 内容生成工具结果摘要（与 {@link #summarizeToolResponseForUi} 语义一致）。
     */
    private static String summarizeToolResponseJsonContent(String contentJson) {
        if (StringUtils.isEmpty(contentJson)) {
            return "";
        }
        try {
            JSONObject root = JSON.parseObject(contentJson);
            JSONArray responses = root.getJSONArray("responses");
            if (responses == null || responses.isEmpty()) {
                return "";
            }
            JSONObject first = responses.getJSONObject(0);
            String name = first.getString("name");
            String data = first.getString("responseData");
            if (name == null) {
                name = "";
            }
            if (data == null) {
                data = "";
            }
            int max = 500;
            if (data.length() > max) {
                data = data.substring(0, max) + "...";
            }
            return "[" + name + "] " + data;
        } catch (Exception e) {
            return contentJson;
        }
    }

    private static String summarizeToolResponseForUi(ToolResponseMessage tr) {
        if (tr.getResponses() == null || tr.getResponses().isEmpty()) {
            return "";
        }
        ToolResponseMessage.ToolResponse r = tr.getResponses().get(0);
        String name = r.name() != null ? r.name() : "";
        String data = r.responseData() != null ? r.responseData() : "";
        int max = 500;
        if (data.length() > max) {
            data = data.substring(0, max) + "...";
        }
        return "[" + name + "] " + data;
    }

    public static ChatContextRecord translateToChatContextRecord(ChatContextBo chatContextBo) {
        ChatContextRecord chatContextRecord = new ChatContextRecord();
        chatContextRecord.setContextId(chatContextBo.getContextId());
        UserMessage lastUserMessage = null;
        for (Message message : chatContextBo.getMessages()) {
            if (message instanceof UserMessage um) {
                lastUserMessage = um;
            }
        }
        if (lastUserMessage != null) {
            String text = lastUserMessage.getText();
            chatContextRecord.setTitle(text.length() > 64 ? text.substring(0, 64) : text);
        }
        if (chatContextRecord.getTitle() == null) {
            chatContextRecord.setTitle(chatContextBo.getTitle());
        }
        chatContextRecord.setUserId(chatContextBo.getUserId());
        chatContextRecord.setMemoryVersion(chatContextBo.getMemoryVersion());
        chatContextRecord.setLastMessageIndex(chatContextBo.getLastMessageIndex());
        chatContextRecord.setUpdateTime(chatContextBo.getUpdateTime());
        return chatContextRecord;
    }

    public static List<ChatContextItem> translateToChatContextItem(ChatContextBo chatContextBo) {
        List<ChatContextItem> chatContextItemList = new ArrayList<>();
        int msgIndex = 0;
        for (int i = 0; i < chatContextBo.getMessages().size(); i++) {
            Message message = chatContextBo.getMessages().get(i);
            ChatContextItem chatContextItem = new ChatContextItem();
            chatContextItem.setMessageId(UUID.randomUUID().toString());
            chatContextItem.setContextId(chatContextBo.getContextId());
            if (message instanceof UserMessage) {
                chatContextItem.setChatRole(1);
                chatContextItem.setMessageIndex(msgIndex++);
            } else if (message instanceof AssistantMessage) {
                chatContextItem.setChatRole(2);
                chatContextItem.setMessageIndex(msgIndex++);
            } else {
                continue;
            }
            chatContextItem.setContent(message.getText());
            chatContextItem.setFeedback(MessageDto.FeedbackEnum.NONE.getValue());
            chatContextItem.setAddTime(System.currentTimeMillis());
            chatContextItemList.add(chatContextItem);
        }
        return chatContextItemList;
    }
}
