package io.github.jerryt92.j2agent.model.security;

import lombok.Data;

import java.util.List;

@Data
public class UserContextBo {
    private String sessionId;
    private String userId;
    private String username;
    /** JWT 或 API_KEY，用于审计和避免 logout 删除长期凭证。 */
    private String credentialType = "JWT";
    /** API Key 的内部 ID；JWT 会话为空。 */
    private String apiKeyId;
    /** 当前会话语言标识（如 zh_CN / en_US）。 */
    private String language;
    private UserRoleEnum role;
    private long expireTime;
    /** 预留细粒度权限，当前为空列表。 */
    private List<String> permissions = List.of();

    public boolean hasAccess(UserRoleEnum requiredRole) {
        if (requiredRole == null) {
            return true;
        }
        if (role == null) {
            return false;
        }
        return role.getValue() <= requiredRole.getValue();
    }

    public boolean isAdmin() {
        return hasAccess(UserRoleEnum.ADMIN);
    }
}
