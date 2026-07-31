package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.constants.ErrorConstants;
import io.github.jerryt92.j2agent.mapper.mgb.ChatContextItemMapper;
import io.github.jerryt92.j2agent.mapper.mgb.ChatContextRecordMapper;
import io.github.jerryt92.j2agent.model.HistoryContextItem;
import io.github.jerryt92.j2agent.model.HistoryContextList;
import io.github.jerryt92.j2agent.model.MessageFeedbackRequest;
import io.github.jerryt92.j2agent.model.Translator;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextItem;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextItemExample;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextRecord;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextRecordExample;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextRecordKey;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentCleanupService;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import io.github.jerryt92.j2agent.service.security.LoginService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 对话上下文服务
 */
@Slf4j
@Service
@EnableScheduling
public class ChatContextService {
    private final ChatContextRecordMapper chatContextRecordMapper;
    private final ChatContextItemMapper chatContextItemMapper;
    private final ChatMemoryRepository chatMemoryRepository;
    private final LoginService loginService;
    private final ChatAttachmentCleanupService attachmentCleanupService;
    private final ActiveChatTurnRegistry activeChatTurnRegistry;

    public ChatContextService(ChatContextRecordMapper chatContextRecordMapper,
                              ChatContextItemMapper chatContextItemMapper,
                              ChatMemoryRepository chatMemoryRepository,
                              LoginService loginService,
                              ActiveChatTurnRegistry activeChatTurnRegistry,
                              @Autowired(required = false) ChatAttachmentCleanupService attachmentCleanupService) {
        this.chatContextRecordMapper = chatContextRecordMapper;
        this.chatContextItemMapper = chatContextItemMapper;
        this.chatMemoryRepository = chatMemoryRepository;
        this.loginService = loginService;
        this.activeChatTurnRegistry = activeChatTurnRegistry;
        this.attachmentCleanupService = attachmentCleanupService;
    }

    /**
     * 要求 agentId 非空，与会话键 / 库表主键一致。
     */
    private static String requireAgentId(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("agentId must not be blank.");
        }
        return agentId.trim();
    }

    /**
     * 判断用户是否可使用该 contextId：无记录视为新会话；有记录则须存在 user_id 匹配的行。
     * 无 userId 时：仅允许尚不存在的新 contextId，已有记录一律拒绝（防匿名越权读）。
     */
    public boolean userOwnsContext(String contextId, String userId) {
        ChatContextRecordExample anyExample = new ChatContextRecordExample();
        anyExample.createCriteria().andContextIdEqualTo(contextId);
        boolean exists = chatContextRecordMapper.countByExample(anyExample) > 0;
        if (!StringUtils.hasText(userId)) {
            return !exists;
        }
        if (!exists) {
            return true;
        }
        ChatContextRecordExample ownedExample = new ChatContextRecordExample();
        ownedExample.createCriteria().andContextIdEqualTo(contextId).andUserIdEqualTo(userId);
        return chatContextRecordMapper.countByExample(ownedExample) > 0;
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    public void checkChatContextToDbTask() {
        try {
            checkChatContextToDb();
        } catch (Throwable t) {
            log.error("", t);
        }
    }

    /**
     * 按 contextId + agentId 取一条会话记录并加载 Spring AI 记忆中的消息列表。
     * <p>userId 必填：必须与记录归属一致，否则返回 null（防越权）。
     */
    public ChatContextBo getChatContext(String contextId, String userId, String agentId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        String aid = requireAgentId(agentId);
        ChatContextRecordKey chatContextRecordKey = new ChatContextRecordKey();
        chatContextRecordKey.setContextId(contextId);
        chatContextRecordKey.setAgentId(aid);
        ChatContextRecord chatContextRecord = chatContextRecordMapper.selectByPrimaryKey(chatContextRecordKey);
        if (chatContextRecord == null) {
            return null;
        }
        if (!userId.equals(chatContextRecord.getUserId())) {
            return null;
        }
        String recordUserId = chatContextRecord.getUserId();
        String conversationId = ConversationIdCodec.format(
                recordUserId == null ? "anonymous" : recordUserId,
                contextId,
                aid);
        List<Message> memoryMessages = chatMemoryRepository.findByConversationId(conversationId);
        return new ChatContextBo(
                contextId,
                chatContextRecord.getUserId(),
                aid,
                chatContextRecord.getTitle(),
                chatContextRecord.getMemoryVersion(),
                chatContextRecord.getLastMessageIndex(),
                chatContextRecord.getUpdateTime(),
                memoryMessages
        );
    }

    /**
     * 删除历史：传入 agentId 时仅删该智能体行；否则删除当前用户在每个 contextId 下全部智能体行。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void deleteHistoryContext(List<String> contextIds, String agentId) {
        UserContextBo session = loginService.getSession();
        if (session == null) {
            return;
        }
        for (String contextId : contextIds) {
            if (StringUtils.hasText(agentId)) {
                if (activeChatTurnRegistry.isActive(contextId, requireAgentId(agentId))) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            ErrorConstants.CHAT_CONTEXT_IN_PROGRESS);
                }
            } else if (activeChatTurnRegistry.isAnyActive(contextId)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        ErrorConstants.CHAT_CONTEXT_IN_PROGRESS);
            }
        }
        String uid = session.getUserId();
        for (String contextId : contextIds) {
            deleteOneContextRecord(uid, contextId, agentId);
        }
    }

    /**
     * 清空当前用户历史；可选按 agentId 过滤。运行中的会话跳过，不抛错。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void clearAllHistoryContext(String agentId) {
        UserContextBo session = loginService.getSession();
        if (session == null) {
            return;
        }
        String uid = session.getUserId();
        ChatContextRecordExample example = new ChatContextRecordExample();
        var criteria = example.createCriteria().andUserIdEqualTo(uid);
        if (StringUtils.hasText(agentId)) {
            criteria.andAgentIdEqualTo(requireAgentId(agentId));
        }
        List<ChatContextRecord> rows = chatContextRecordMapper.selectByExample(example);
        for (ChatContextRecord record : rows) {
            String contextId = record.getContextId();
            String aid = record.getAgentId();
            if (!StringUtils.hasText(aid)) {
                continue;
            }
            if (activeChatTurnRegistry.isActive(contextId, aid)) {
                continue;
            }
            if (StringUtils.hasText(agentId)) {
                deleteOneContextRecord(uid, contextId, agentId);
            } else {
                chatMemoryRepository.deleteByConversationId(ConversationIdCodec.format(uid, contextId, aid));
                if (!hasContextRecords(contextId)) {
                    if (attachmentCleanupService != null) {
                        attachmentCleanupService.deleteByChatContextPrefix(uid, contextId);
                    }
                }
            }
        }
    }

    private void deleteOneContextRecord(String uid, String contextId, String agentId) {
        if (StringUtils.hasText(agentId)) {
            String aid = requireAgentId(agentId);
            ChatContextRecordKey chatContextRecordKey = new ChatContextRecordKey();
            chatContextRecordKey.setContextId(contextId);
            chatContextRecordKey.setAgentId(aid);
            ChatContextRecord record = chatContextRecordMapper.selectByPrimaryKey(chatContextRecordKey);
            if (record != null && uid.equals(record.getUserId())) {
                chatMemoryRepository.deleteByConversationId(ConversationIdCodec.format(uid, contextId, aid));
            }
        } else {
            ChatContextRecordExample ex = new ChatContextRecordExample();
            ex.createCriteria().andContextIdEqualTo(contextId).andUserIdEqualTo(uid);
            List<ChatContextRecord> rows = chatContextRecordMapper.selectByExample(ex);
            for (ChatContextRecord r : rows) {
                String aid = r.getAgentId();
                if (!StringUtils.hasText(aid)) {
                    continue;
                }
                chatMemoryRepository.deleteByConversationId(ConversationIdCodec.format(uid, contextId, aid));
            }
            if (rows.isEmpty() || !hasContextRecords(contextId)) {
                if (attachmentCleanupService != null) {
                    attachmentCleanupService.deleteByChatContextPrefix(uid, contextId);
                }
            }
        }
    }

    private boolean hasContextRecords(String contextId) {
        ChatContextRecordExample example = new ChatContextRecordExample();
        example.createCriteria().andContextIdEqualTo(contextId);
        return chatContextRecordMapper.countByExample(example) > 0;
    }

    /**
     * 历史列表；可选按 agent-id 过滤。
     */
    public HistoryContextList getHistoryContextList(Integer offset, Integer limit, String agentIdFilter) {
        UserContextBo session = loginService.getSession();
        if (session == null) {
            return new HistoryContextList().data(new ArrayList<>());
        }
        ChatContextRecordExample chatContextRecordExample = new ChatContextRecordExample();
        chatContextRecordExample.setOrderByClause("update_time desc");
        chatContextRecordExample.setOffset(offset);
        chatContextRecordExample.setLimit(limit);
        var criteria = chatContextRecordExample.createCriteria().andUserIdEqualTo(session.getUserId());
        if (StringUtils.hasText(agentIdFilter)) {
            criteria.andAgentIdEqualTo(requireAgentId(agentIdFilter));
        }
        List<ChatContextRecord> chatContextRecordList = chatContextRecordMapper.selectByExample(chatContextRecordExample);
        List<HistoryContextItem> historyContextItemList = new ArrayList<>();
        for (ChatContextRecord chatContextRecord : chatContextRecordList) {
            historyContextItemList.add(Translator.translateToHistoryContextItem(chatContextRecord));
        }
        return new HistoryContextList().data(historyContextItemList);
    }

    private void checkChatContextToDb() {
        // 使用 ChatMemoryRepository 统一持久化后，这里不再需要缓存刷盘任务。
    }

    /**
     * 消息反馈：请求体须带非空 agentId。
     */
    public void addMessageFeedback(MessageFeedbackRequest messageFeedbackRequest) {
        UserContextBo session = loginService.getSession();
        if (session != null) {
            String aid = requireAgentId(messageFeedbackRequest.getAgentId());
            ChatContextBo chatContextBo = getChatContext(messageFeedbackRequest.getContextId(), session.getUserId(), aid);
            if (chatContextBo != null) {
                ChatContextItemExample example = new ChatContextItemExample();
                example.createCriteria()
                        .andContextIdEqualTo(messageFeedbackRequest.getContextId())
                        .andAgentIdEqualTo(aid)
                        .andMessageIndexEqualTo(messageFeedbackRequest.getIndex());
                example.setOrderByClause("add_time desc");
                List<ChatContextItem> rows = chatContextItemMapper.selectByExample(example);
                if (CollectionUtils.isEmpty(rows)) {
                    return;
                }
                ChatContextItem update = new ChatContextItem();
                update.setMessageId(rows.get(0).getMessageId());
                update.setFeedback(messageFeedbackRequest.getFeedback().getValue());
                chatContextItemMapper.updateByPrimaryKeySelective(update);
            }
        }
    }
}
