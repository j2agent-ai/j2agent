package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.config.storage.ObjectStorageProperties;
import io.github.jerryt92.j2agent.controller.ChatFileController;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.ChatContextDto;
import io.github.jerryt92.j2agent.model.MessageDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 按 {@link ObjectStorageProperties#getAccessMode()} 为聊天附件生成展示 URL。
 */
@Service
@ConditionalOnBean(ObjectFileManagementService.class)
public class ChatAttachmentUrlResolver {

    /** 聊天气泡展示用预签名 URL 有效期（DIRECT 模式；过期后前端可调用 preview API 刷新）。 */
    public static final Duration DISPLAY_URL_TTL = Duration.ofHours(24);

    private final ObjectFileManagementService fileService;
    private final ObjectStorageProperties storageProperties;

    public ChatAttachmentUrlResolver(ObjectFileManagementService fileService,
                                     ObjectStorageProperties storageProperties) {
        this.fileService = fileService;
        this.storageProperties = storageProperties;
    }

    public boolean isDirectAccess() {
        return storageProperties.getAccessMode()
                == ObjectStorageProperties.AccessMode.DIRECT;
    }

    public String displayUrl(String objectKey) {
        if (storageProperties.getAccessMode()
                != ObjectStorageProperties.AccessMode.DIRECT) {
            return ChatFileController.stableContentUrl(objectKey);
        }
        return fileService.previewUrl(objectKey, DISPLAY_URL_TTL).toString();
    }

    public List<ChatAttachmentDto> withDisplayUrls(List<ChatAttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<ChatAttachmentDto> resolved = new ArrayList<>(attachments.size());
        for (ChatAttachmentDto attachment : attachments) {
            if (attachment == null || !StringUtils.hasText(attachment.getObjectKey())) {
                continue;
            }
            ChatAttachmentDto copy = copyMetadata(attachment);
            copy.setUrl(displayUrl(attachment.getObjectKey()));
            resolved.add(copy);
        }
        return List.copyOf(resolved);
    }

    public void applyToChatContext(ChatContextDto dto) {
        if (dto == null) {
            return;
        }
        applyToMessages(dto.getMessages());
    }

    /** 为消息列表中的附件填充展示 URL（审计详情等复用）。 */
    public void applyToMessages(List<MessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (MessageDto message : messages) {
            if (message.getAttachments() == null || message.getAttachments().isEmpty()) {
                continue;
            }
            message.setAttachments(withDisplayUrls(message.getAttachments()));
        }
    }

    public static ChatAttachmentDto copyMetadata(ChatAttachmentDto attachment) {
        ChatAttachmentDto copy = new ChatAttachmentDto();
        copy.setObjectKey(attachment.getObjectKey());
        copy.setName(attachment.getName());
        copy.setContentType(attachment.getContentType());
        copy.setSize(attachment.getSize());
        return copy;
    }
}
