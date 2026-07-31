package io.github.jerryt92.j2agent.service.audit;

import io.github.jerryt92.j2agent.mapper.ext.AuditChatContextExtMapper;
import io.github.jerryt92.j2agent.mapper.ext.LlmUsageRecordMapper;
import io.github.jerryt92.j2agent.mapper.mgb.ChatContextItemMapper;
import io.github.jerryt92.j2agent.mapper.mgb.ChatContextRecordMapper;
import io.github.jerryt92.j2agent.mapper.mgb.UserPoMapper;
import io.github.jerryt92.j2agent.model.AuditContextDetailDto;
import io.github.jerryt92.j2agent.model.AuditContextItemDto;
import io.github.jerryt92.j2agent.model.AuditContextListDto;
import io.github.jerryt92.j2agent.model.AuditTokenRecordDto;
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
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextRecordKey;
import io.github.jerryt92.j2agent.model.po.mgb.UserPo;
import io.github.jerryt92.j2agent.model.po.mgb.UserPoExample;
import io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentUrlResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

    @Autowired(required = false)
    private ChatAttachmentUrlResolver chatAttachmentUrlResolver;

    public AuditService(LlmUsageRecordMapper llmUsageRecordMapper,
                        ChatContextRecordMapper chatContextRecordMapper,
                        ChatContextItemMapper chatContextItemMapper,
                        AuditChatContextExtMapper auditChatContextExtMapper,
                        UserPoMapper userPoMapper) {
        this.llmUsageRecordMapper = llmUsageRecordMapper;
        this.chatContextRecordMapper = chatContextRecordMapper;
        this.chatContextItemMapper = chatContextItemMapper;
        this.auditChatContextExtMapper = auditChatContextExtMapper;
        this.userPoMapper = userPoMapper;
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

    private AuditTokenSummaryItemDto toSummaryItem(LlmUsageSummaryRow row) {
        return new AuditTokenSummaryItemDto()
                .userId(row.getUserId())
                .username(row.getUsername())
                .callCount(nz(row.getCallCount()))
                .inputTokens(nz(row.getInputTokens()))
                .outputTokens(nz(row.getOutputTokens()))
                .billableTokens(nz(row.getBillableTokens()));
    }

    private AuditTokenRecordDto toRecordItem(LlmUsageRecordQueryRow row) {
        return new AuditTokenRecordDto()
                .id(row.getId())
                .userId(row.getUserId())
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
