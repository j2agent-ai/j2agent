package io.github.jerryt92.j2agent.service.audit;

import io.github.jerryt92.j2agent.mapper.ext.AuditChatContextExtMapper;
import io.github.jerryt92.j2agent.mapper.ext.LlmUsageRecordMapper;
import io.github.jerryt92.j2agent.mapper.mgb.ChatContextItemMapper;
import io.github.jerryt92.j2agent.mapper.mgb.ChatContextRecordMapper;
import io.github.jerryt92.j2agent.mapper.mgb.UserPoMapper;
import io.github.jerryt92.j2agent.model.AuditContextDetailDto;
import io.github.jerryt92.j2agent.model.AuditContextDeleteItemDto;
import io.github.jerryt92.j2agent.model.AuditContextDeleteRequestDto;
import io.github.jerryt92.j2agent.model.AuditContextItemDto;
import io.github.jerryt92.j2agent.model.AuditContextListDto;
import io.github.jerryt92.j2agent.model.AuditTokenRecordDto;
import io.github.jerryt92.j2agent.model.AuditTokenRecordDeleteRequestDto;
import io.github.jerryt92.j2agent.model.AuditTokenUserDeleteRequestDto;
import io.github.jerryt92.j2agent.model.AuditTokenRecordListDto;
import io.github.jerryt92.j2agent.model.AuditTokenSummaryDto;
import io.github.jerryt92.j2agent.model.AuditTokenSummaryItemDto;
import io.github.jerryt92.j2agent.model.MessageDto;
import io.github.jerryt92.j2agent.model.Translator;
import io.github.jerryt92.j2agent.model.po.LlmUsageRecordQueryRow;
import io.github.jerryt92.j2agent.model.po.LlmUsageSummaryRow;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextItem;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextItemExample;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextRecord;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextRecordExample;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextRecordKey;
import io.github.jerryt92.j2agent.model.po.mgb.UserPo;
import io.github.jerryt92.j2agent.model.po.mgb.UserPoExample;
import io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentUrlResolver;
import io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentCleanupService;
import io.github.jerryt92.j2agent.service.llm.ActiveChatTurnRegistry;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理员审计查询：Token 用量与跨用户聊天记录（独立读写，不经用户态 ChatContextService）。
 */
@Service
public class AuditService {

    private final LlmUsageRecordMapper llmUsageRecordMapper;
    private final ChatContextRecordMapper chatContextRecordMapper;
    private final ChatContextItemMapper chatContextItemMapper;
    private final AuditChatContextExtMapper auditChatContextExtMapper;
    private final UserPoMapper userPoMapper;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ActiveChatTurnRegistry activeChatTurnRegistry;

    @Autowired(required = false)
    private ChatAttachmentUrlResolver chatAttachmentUrlResolver;

    @Autowired(required = false)
    private ChatAttachmentCleanupService attachmentCleanupService;

    public AuditService(LlmUsageRecordMapper llmUsageRecordMapper,
                        ChatContextRecordMapper chatContextRecordMapper,
                        ChatContextItemMapper chatContextItemMapper,
                        AuditChatContextExtMapper auditChatContextExtMapper,
                        UserPoMapper userPoMapper,
                        ChatMemoryRepository chatMemoryRepository,
                        ActiveChatTurnRegistry activeChatTurnRegistry) {
        this.llmUsageRecordMapper = llmUsageRecordMapper;
        this.chatContextRecordMapper = chatContextRecordMapper;
        this.chatContextItemMapper = chatContextItemMapper;
        this.auditChatContextExtMapper = auditChatContextExtMapper;
        this.userPoMapper = userPoMapper;
        this.chatMemoryRepository = chatMemoryRepository;
        this.activeChatTurnRegistry = activeChatTurnRegistry;
    }

    /** Token 用量按用户聚合总览。 */
    public AuditTokenSummaryDto getTokenSummary(String userId,
                                                String username,
                                                Long from,
                                                Long to,
                                                Integer offset,
                                                Integer limit) {
        int off = normalizeOffset(offset);
        int lim = normalizeLimit(limit);
        String uid = blankToNull(userId);
        String uname = blankToNull(username);

        long total = llmUsageRecordMapper.countSummaryByUser(uid, uname, from, to);
        List<LlmUsageSummaryRow> rows = llmUsageRecordMapper.selectSummaryByUser(uid, uname, from, to, off, lim);
        LlmUsageSummaryRow globals = llmUsageRecordMapper.selectGlobalTotals(uid, uname, from, to);

        AuditTokenSummaryDto dto = new AuditTokenSummaryDto();
        dto.setTotal(total);
        dto.setData(rows.stream().map(this::toSummaryItem).toList());
        if (globals != null) {
            dto.setGlobalCallCount(nz(globals.getCallCount()));
            dto.setGlobalInputTokens(nz(globals.getInputTokens()));
            dto.setGlobalOutputTokens(nz(globals.getOutputTokens()));
            dto.setGlobalBillableTokens(nz(globals.getBillableTokens()));
        } else {
            dto.setGlobalCallCount(0L);
            dto.setGlobalInputTokens(0L);
            dto.setGlobalOutputTokens(0L);
            dto.setGlobalBillableTokens(0L);
        }
        return dto;
    }

    /** Token 调用明细。 */
    public AuditTokenRecordListDto getTokenRecords(String userId,
                                                   String agentId,
                                                   String modelName,
                                                   String callKind,
                                                   String usageStatus,
                                                   Long from,
                                                   Long to,
                                                   Integer offset,
                                                   Integer limit) {
        int off = normalizeOffset(offset);
        int lim = normalizeLimit(limit);
        String uid = blankToNull(userId);
        String aid = blankToNull(agentId);
        String model = blankToNull(modelName);
        String kind = blankToNull(callKind);
        String status = blankToNull(usageStatus);

        long total = llmUsageRecordMapper.countRecords(uid, aid, model, kind, status, from, to);
        List<LlmUsageRecordQueryRow> rows =
                llmUsageRecordMapper.selectRecords(uid, aid, model, kind, status, from, to, off, lim);

        AuditTokenRecordListDto dto = new AuditTokenRecordListDto();
        dto.setTotal(total);
        dto.setData(rows.stream().map(this::toRecordItem).toList());
        return dto;
    }

    /** 查询聊天会话列表；userId 可选。按 update_time 倒序（context_id 编码后无法按字符串还原 UUIDv7 时间序）。 */
    public AuditContextListDto getContexts(String userId,
                                           String title,
                                           String agentId,
                                           Long from,
                                           Long to,
                                           Integer offset,
                                           Integer limit) {
        int off = normalizeOffset(offset);
        int lim = normalizeLimit(limit);
        String uid = blankToNull(userId);
        String titleKw = blankToNull(title);
        String aid = blankToNull(agentId);

        long total = auditChatContextExtMapper.countContexts(uid, titleKw, aid, from, to);
        List<ChatContextRecord> records =
                auditChatContextExtMapper.selectContexts(uid, titleKw, aid, from, to, off, lim);

        Map<String, String> usernameById = loadUsernames(records.stream()
                .map(ChatContextRecord::getUserId)
                .toList());

        List<AuditContextItemDto> items = new ArrayList<>(records.size());
        for (ChatContextRecord record : records) {
            String ownerId = blankToNull(record.getUserId());
            items.add(toContextItem(record, ownerId == null ? null : usernameById.get(ownerId)));
        }
        AuditContextListDto dto = new AuditContextListDto();
        dto.setTotal(total);
        dto.setData(items);
        return dto;
    }

    /**
     * 审计专用会话详情：仅按 contextId + agentId 主键直读，不依赖客户端传 user-id（避免误 404 / 越权伪装）。
     */
    public AuditContextDetailDto getContext(String contextId, String agentId) {
        if (!StringUtils.hasText(contextId) || !StringUtils.hasText(agentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "context-id and agent-id are required");
        }
        String cid = contextId.trim();
        String aid = agentId.trim();

        ChatContextRecordKey key = new ChatContextRecordKey();
        key.setContextId(cid);
        key.setAgentId(aid);
        ChatContextRecord record = chatContextRecordMapper.selectByPrimaryKey(key);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "context not found");
        }

        ChatContextItemExample itemExample = new ChatContextItemExample();
        itemExample.createCriteria()
                .andContextIdEqualTo(cid)
                .andAgentIdEqualTo(aid)
                .andChatRoleNotEqualTo(0);
        itemExample.setOrderByClause("message_index asc");
        List<ChatContextItem> items = chatContextItemMapper.selectByExample(itemExample);

        List<MessageDto> messages = new ArrayList<>(items.size());
        for (ChatContextItem item : items) {
            messages.add(Translator.translateToChatMessageDto(item));
        }

        String ownerUserId = blankToNull(record.getUserId());
        String username = ownerUserId == null ? null : loadUsernames(List.of(ownerUserId)).get(ownerUserId);

        AuditContextDetailDto dto = new AuditContextDetailDto()
                .contextId(record.getContextId())
                .agentId(record.getAgentId())
                .userId(ownerUserId)
                .username(username)
                .title(record.getTitle())
                .lastUpdateTime(record.getUpdateTime())
                .messages(messages);
        if (chatAttachmentUrlResolver != null) {
            chatAttachmentUrlResolver.applyToMessages(messages);
        }
        return dto;
    }

    /** 删除管理员明确选择的 Token 用量审计记录。 */
    @Transactional(rollbackFor = Throwable.class)
    public void deleteTokenRecords(AuditTokenRecordDeleteRequestDto request) {
        List<String> ids = normalizeIds(request == null ? null : request.getIds());
        if (llmUsageRecordMapper.countByIds(ids) != ids.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "audit token record not found");
        }
        llmUsageRecordMapper.deleteByIds(ids);
    }

    /** 删除总览用户的全部 Token 明细，不受当前总览筛选条件限制。 */
    @Transactional(rollbackFor = Throwable.class)
    public void deleteTokenUsers(AuditTokenUserDeleteRequestDto request) {
        List<String> userIds = normalizeIds(request == null ? null : request.getUserIds());
        if (llmUsageRecordMapper.countByUserIds(userIds) < userIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "audit token user not found");
        }
        llmUsageRecordMapper.deleteByUserIds(userIds);
    }

    /**
     * 删除管理员明确选择的会话。预先校验所有主键和运行状态，避免产生部分删除。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void deleteContexts(AuditContextDeleteRequestDto request) {
        List<AuditContextDeleteItemDto> items = normalizeContextItems(request == null ? null : request.getItems());
        List<ChatContextRecord> records = new ArrayList<>(items.size());
        for (AuditContextDeleteItemDto item : items) {
            ChatContextRecordKey key = new ChatContextRecordKey();
            key.setContextId(item.getContextId());
            key.setAgentId(item.getAgentId());
            ChatContextRecord record = chatContextRecordMapper.selectByPrimaryKey(key);
            if (record == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "audit context not found");
            }
            if (activeChatTurnRegistry.isActive(item.getContextId(), item.getAgentId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "audit context is in progress");
            }
            records.add(record);
        }
        for (ChatContextRecord record : records) {
            String ownerId = blankToNull(record.getUserId());
            String conversationUserId = ownerId == null ? "anonymous" : ownerId;
            chatMemoryRepository.deleteByConversationId(ConversationIdCodec.format(
                    conversationUserId, record.getContextId(), record.getAgentId()));
            if (ownerId != null && !hasContextRecords(record.getContextId()) && attachmentCleanupService != null) {
                attachmentCleanupService.deleteByChatContextPrefix(ownerId, record.getContextId());
            }
        }
    }

    private AuditContextItemDto toContextItem(ChatContextRecord record, String username) {
        return new AuditContextItemDto()
                .contextId(record.getContextId())
                .agentId(record.getAgentId())
                .userId(blankToNull(record.getUserId()))
                .username(username)
                .title(record.getTitle())
                .lastUpdateTime(record.getUpdateTime());
    }

    /** 批量解析 userId → username */
    private Map<String, String> loadUsernames(List<String> userIds) {
        Map<String, String> map = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return map;
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String id : userIds) {
            if (StringUtils.hasText(id)) {
                distinct.add(id.trim());
            }
        }
        if (distinct.isEmpty()) {
            return map;
        }
        UserPoExample example = new UserPoExample();
        example.createCriteria().andIdIn(new ArrayList<>(distinct));
        for (UserPo user : userPoMapper.selectByExample(example)) {
            if (user == null || !StringUtils.hasText(user.getId())) {
                continue;
            }
            // CHAR 主键可能带尾部空格，统一 trim 再映射
            map.put(user.getId().trim(), user.getUsername());
        }
        return map;
    }

    private boolean hasContextRecords(String contextId) {
        ChatContextRecordExample example = new ChatContextRecordExample();
        example.createCriteria().andContextIdEqualTo(contextId);
        return chatContextRecordMapper.countByExample(example) > 0;
    }

    private static List<String> normalizeIds(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "audit record ids are required");
        }
        List<String> ids = new ArrayList<>(rawIds.size());
        Set<String> unique = new LinkedHashSet<>();
        for (String rawId : rawIds) {
            String id = blankToNull(rawId);
            if (id == null || !unique.add(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "audit record ids must be unique and non-blank");
            }
            ids.add(id);
        }
        return ids;
    }

    private static List<AuditContextDeleteItemDto> normalizeContextItems(List<AuditContextDeleteItemDto> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "audit contexts are required");
        }
        List<AuditContextDeleteItemDto> items = new ArrayList<>(rawItems.size());
        Set<String> unique = new LinkedHashSet<>();
        for (AuditContextDeleteItemDto rawItem : rawItems) {
            String contextId = rawItem == null ? null : blankToNull(rawItem.getContextId());
            String agentId = rawItem == null ? null : blankToNull(rawItem.getAgentId());
            if (contextId == null || agentId == null || !unique.add(contextId + '\u0000' + agentId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "audit contexts must have unique, non-blank contextId and agentId");
            }
            items.add(new AuditContextDeleteItemDto().contextId(contextId).agentId(agentId));
        }
        return items;
    }

    private AuditTokenSummaryItemDto toSummaryItem(LlmUsageSummaryRow row) {
        return new AuditTokenSummaryItemDto()
                .userId(blankToNull(row.getUserId()))
                .username(row.getUsername())
                .callCount(nz(row.getCallCount()))
                .inputTokens(nz(row.getInputTokens()))
                .outputTokens(nz(row.getOutputTokens()))
                .billableTokens(nz(row.getBillableTokens()));
    }

    private AuditTokenRecordDto toRecordItem(LlmUsageRecordQueryRow row) {
        return new AuditTokenRecordDto()
                .id(row.getId())
                .userId(blankToNull(row.getUserId()))
                .username(row.getUsername())
                .contextId(row.getContextId())
                .agentId(row.getAgentId())
                .turnId(row.getTurnId())
                .callSeq(row.getCallSeq())
                .callKind(row.getCallKind())
                .providerType(row.getProviderType())
                .modelName(row.getModelName())
                .inputTokens(row.getInputTokens())
                .outputTokens(row.getOutputTokens())
                .totalTokens(row.getTotalTokens())
                .billableTokenCount(row.getBillableTokenCount())
                .usageStatus(row.getUsageStatus())
                .createTime(row.getCreateTime());
    }

    private static int normalizeOffset(Integer offset) {
        return offset == null || offset < 0 ? 0 : offset;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
