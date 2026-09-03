package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

/**
 * Permission row used to populate the Redis ACL cache.
 */
@Data
public class ResourcePermissionRow {
    private String resourceId;
    private Integer permissionLevel;
    private Long expiresAt;
}
