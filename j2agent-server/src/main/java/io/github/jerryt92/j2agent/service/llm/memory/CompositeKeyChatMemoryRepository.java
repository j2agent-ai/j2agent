package io.github.jerryt92.j2agent.service.llm.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.jerryt92.j2agent.mapper.ext.ChatMemoryExtMapper;
import io.github.jerryt92.j2agent.mapper.mgb.ChatContextItemMapper;
import io.github.jerryt92.j2agent.mapper.mgb.ChatContextRecordMapper;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextItem;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextItemExample;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextRecord;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextRecordExample;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextRecordKey;
import io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentCleanupService;
import io.github.jerryt92.j2agent.service.file.oss.ObjectFileReferenceService;
import io.github.jerryt92.j2agent.service.llm.reasoning.SpringAiReasoningMetadataAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 复合会话键（userId:contextId:agentId）下的对话记忆 JDBC 持久化实现。
 */
@Slf4j
@Component("jdbcChatMemoryRepository")
@Qualifier("jdbcChatMemoryRepository")
public class CompositeKeyChatMemoryRepository implements ChatMemoryRepository {

    public static final String PLACEHOLDER_TITLE = "New Chat";
    public static final String IMAGE_ONLY_TITLE = "__image_only__";
    private static final int TITLE_MAX_LENGTH = 64;

    private final ChatContextRecordMapper chatContextRecordMapper;
    private final ChatContextItemMapper chatContextItemMapper;
    private final ChatMemoryExtMapper chatMemoryExtMapper;
    private final ChatMemoryMessageCodec messageCodec;
    private final ObjectFileReferenceService fileReferenceService;
    private final ChatAttachmentCleanupService attachmentCleanupService;

    public CompositeKeyChatMemoryRepository(ChatContextRecordMapper chatContextRecordMapper,
                                            ChatContextItemMapper chatContextItemMapper,
                                            ChatMemoryExtMapper chatMemoryExtMapper,
                                            ChatMemoryMessageCodec messageCodec,
                                            ObjectFileReferenceService fileReferenceService,
                                            @Autowired(required = false) ChatAttachmentCleanupService attachmentCleanupService) {
        this.chatContextRecordMapper = chatContextRecordMapper;
        this.chatContextItemMapper = chatContextItemMapper;
        this.chatMemoryExtMapper = chatMemoryExtMapper;
        this.messageCodec = messageCodec;
        this.fileReferenceService = fileReferenceService;
        this.attachmentCleanupService = attachmentCleanupService;
    }

    /**
     * 返回所有会话ID，统一拼接为 userId:contextId:agentId。
     */
    @Override
    public List<String> findConversationIds() {
        ChatContextRecordExample example = new ChatContextRecordExample();
        return chatContextRecordMapper.selectByExample(example).stream()
                .filter(record -> StringUtils.hasText(record.getAgentId()))
                .map(record -> ConversationIdCodec.format(
                        record.getUserId() == null ? "anonymous" : record.getUserId(),
                        record.getContextId(),
                        record.getAgentId()))
                .collect(Collectors.toList());
    }

    /**
     * 按会话ID读取历史消息，并转换成 Spring AI 消息模型。
     */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        ConversationIdCodec.Parts parts = ConversationIdCodec.parse(conversationId);
        ChatContextItemExample example = new ChatContextItemExample();
        example.createCriteria()
                .andContextIdEqualTo(parts.contextId())
                .andAgentIdEqualTo(parts.agentId())
                .andChatRoleNotEqualTo(0);
        example.setOrderByClause("message_index asc");
        List<ChatContextItem> items = chatContextItemMapper.selectByExample(example);
        List<Message> messages = new ArrayList<>();
        for (ChatContextItem item : items) {
            Message m = messageCodec.decode(item.getChatRole(), item.getContent(), item.getMetaJson(),
                    item.getRagInfos());
            if (m != null) {
                messages.add(m);
            }
        }
        return messages;
    }

    /**
     * 持久化本批新增消息（delta），按 {@code lastMessageIndex + 1} 连续追加；支持 user、assistant（含 tool_calls）、tool 响应；忽略 system。
     * <p>
     * 调用方须只传入本次 {@link org.springframework.ai.chat.memory.ChatMemory#add} 的新消息，
     * 勿传整段会话列表（旧版按列表下标与 {@code message_index} 对齐的写法已废弃）。
     */
    @Override
    @Transactional(rollbackFor = Throwable.class)
    public void saveAll(String conversationId, List<Message> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return;
        }
        ConversationIdCodec.Parts parts = ConversationIdCodec.parse(conversationId);
        ensureContextRecord(parts, messages);
        updateTitleFromUserMessages(parts, messages);
        String agentId = parts.agentId();
        Integer lastMessageIndex = chatMemoryExtMapper.selectLastMessageIndexForUpdate(parts.contextId(), agentId);
        int nextIndex = lastMessageIndex == null ? 0 : lastMessageIndex + 1;

        int insertCount = 0;
        for (Message message : messages) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (message instanceof AssistantMessage am) {
                if (!am.hasToolCalls() && !StringUtils.hasText(am.getText()) && !hasReasoningMetadata(am)) {
                    continue;
                }
            }
            ChatMemoryMessageCodec.PersistedRow row;
            try {
                row = messageCodec.encode(message);
            } catch (JsonProcessingException e) {
                continue;
            }
            if (row == null) {
                continue;
            }
            if (chatMemoryExtMapper.existsByContextAgentIndexRoleContent(
                    parts.contextId(), agentId, nextIndex, row.chatRole(), row.content()) > 0) {
                nextIndex++;
                continue;
            }
            logOversizedPersistAttempt(message, row, parts.contextId(), agentId, nextIndex);
            String messageId = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            chatMemoryExtMapper.insertChatContextItem(
                    parts.contextId(),
                    agentId,
                    nextIndex,
                    row.chatRole(),
                    row.content(),
                    0,
                    row.ragInfos(),
                    now,
                    messageId,
                    row.metaJson()
            );
            nextIndex++;
            insertCount++;
        }
        if (insertCount > 0) {
            chatMemoryExtMapper.updateRecordCursor(parts.contextId(), agentId, nextIndex - 1, System.currentTimeMillis());
        }
    }

    /**
     * 删除会话对应的上下文记录与消息明细。
     */
    @Override
    @Transactional(rollbackFor = Throwable.class)
    public void deleteByConversationId(String conversationId) {
        ConversationIdCodec.Parts parts = ConversationIdCodec.parse(conversationId);
        String agentId = parts.agentId();
        ChatContextItemExample itemExample = new ChatContextItemExample();
        itemExample.createCriteria()
                .andContextIdEqualTo(parts.contextId())
                .andAgentIdEqualTo(agentId);
        List<String> fileIds = fileReferenceService.findChatFileIds(parts.contextId(), agentId);
        fileReferenceService.removeChatReferences(parts.contextId(), agentId);
        if (attachmentCleanupService != null) {
            attachmentCleanupService.cleanupOrphanFiles(fileIds);
        }
        chatContextItemMapper.deleteByExample(itemExample);
        ChatContextRecordKey chatContextRecordKey = new ChatContextRecordKey();
        chatContextRecordKey.setContextId(parts.contextId());
        chatContextRecordKey.setAgentId(agentId);
        chatContextRecordMapper.deleteByPrimaryKey(chatContextRecordKey);
    }

    /**
     * 首次落库时确保主记录存在，避免明细孤儿数据。
     */
    private void ensureContextRecord(ConversationIdCodec.Parts parts, List<Message> messages) {
        String agentId = parts.agentId();
        ChatContextRecordKey chatContextRecordKey = new ChatContextRecordKey();
        chatContextRecordKey.setContextId(parts.contextId());
        chatContextRecordKey.setAgentId(agentId);
        ChatContextRecord existing = chatContextRecordMapper.selectByPrimaryKey(chatContextRecordKey);
        if (existing != null) {
            return;
        }
        ChatContextRecord insertRecord = new ChatContextRecord();
        insertRecord.setContextId(parts.contextId());
        insertRecord.setAgentId(agentId);
        insertRecord.setUserId(parts.userId());
        insertRecord.setMemoryVersion(1);
        insertRecord.setLastMessageIndex(-1);
        insertRecord.setTitle(extractTitle(messages));
        long now = System.currentTimeMillis();
        insertRecord.setUpdateTime(now);
        chatContextRecordMapper.insertSelective(insertRecord);
    }

    /**
     * 本批消息含用户消息时，按最后一条用户消息更新标题（纯图片为 {@link #IMAGE_ONLY_TITLE}，有文字则截断）。
     */
    private void updateTitleFromUserMessages(ConversationIdCodec.Parts parts, List<Message> messages) {
        UserMessage lastUserMessage = null;
        for (Message message : messages) {
            if (message instanceof UserMessage um) {
                lastUserMessage = um;
            }
        }
        if (lastUserMessage == null) {
            return;
        }
        String agentId = parts.agentId();
        ChatContextRecordKey chatContextRecordKey = new ChatContextRecordKey();
        chatContextRecordKey.setContextId(parts.contextId());
        chatContextRecordKey.setAgentId(agentId);
        ChatContextRecord record = chatContextRecordMapper.selectByPrimaryKey(chatContextRecordKey);
        if (record == null) {
            return;
        }
        List<ChatAttachmentDto> attachments = ChatMemoryMessageCodec.attachmentsFromUserMessage(lastUserMessage);
        chatMemoryExtMapper.updateTitle(
                parts.contextId(),
                agentId,
                autoTitle(lastUserMessage.getText(), attachments),
                System.currentTimeMillis());
    }

    /**
     * 首次落库时根据首条用户消息生成标题。
     */
    private String extractTitle(List<Message> messages) {
        for (Message message : messages) {
            if (message instanceof UserMessage um) {
                return autoTitle(um.getText(), ChatMemoryMessageCodec.attachmentsFromUserMessage(um));
            }
        }
        return PLACEHOLDER_TITLE;
    }

    public static String autoTitle(String firstUserText, List<ChatAttachmentDto> attachments) {
        if (StringUtils.hasText(firstUserText)) {
            return firstUserText.length() > TITLE_MAX_LENGTH
                    ? firstUserText.substring(0, TITLE_MAX_LENGTH)
                    : firstUserText;
        }
        if (!CollectionUtils.isEmpty(attachments)) {
            return IMAGE_ONLY_TITLE;
        }
        return PLACEHOLDER_TITLE;
    }

    private static boolean hasReasoningMetadata(AssistantMessage am) {
        if (am == null) {
            return false;
        }
        Map<String, Object> metadata = am.getMetadata();
        return metadata.containsKey(SpringAiReasoningMetadataAdapter.UNIFIED_REASONING_KEY);
    }

    private static void logOversizedPersistAttempt(Message message,
                                                   ChatMemoryMessageCodec.PersistedRow row,
                                                   String contextId,
                                                   String agentId,
                                                   int messageIndex) {
        int contentLen = row.content() != null ? row.content().length() : 0;
        if (contentLen <= ChatMemoryMessageCodec.TEXT_CHAR_SAFE_LIMIT) {
            return;
        }
        log.warn(
                "Persisting chat_context_item with content length {} exceeding TEXT limit (type={}, chatRole={}, contextId={}, agentId={}, messageIndex={})",
                contentLen,
                describePersistMessageType(message, row.chatRole()),
                row.chatRole(),
                contextId,
                agentId,
                messageIndex);
    }

    private static String describePersistMessageType(Message message, int chatRole) {
        if (message instanceof UserMessage) {
            return "user";
        }
        if (message instanceof ToolResponseMessage) {
            return "tool_response";
        }
        if (message instanceof AssistantMessage am) {
            if (am.hasToolCalls()) {
                return "assistant_tool";
            }
            return "assistant";
        }
        return "unknown(chatRole=" + chatRole + ")";
    }
}
