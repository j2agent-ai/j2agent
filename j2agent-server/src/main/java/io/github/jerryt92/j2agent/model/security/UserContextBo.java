package io.github.jerryt92.j2agent.model.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Data;

import java.util.List;

@Data
public class UserContextBo {
    private String sessionId;
    private String userId;
    private String username;
    /** 当前会话语言标识（如 zh_CN / en_US）。 */
    private String language;
    private RoleEnum role;
    private long expireTime;
    /** 预留细粒度权限，当前为空列表。 */
    private List<String> permissions = List.of();

    /**
     * 用户角色，数值越小权限越高。
     */
    public enum RoleEnum {
        ADMIN(1),

        USER(2);

        private final Integer value;

        RoleEnum(Integer value) {
            this.value = value;
        }

        @JsonValue
        public Integer getValue() {
            return value;
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }

        @JsonCreator
        public static RoleEnum fromValue(Integer value) {
            for (RoleEnum b : RoleEnum.values()) {
                if (b.value.equals(value)) {
                    return b;
                }
            }
            throw new IllegalArgumentException("Unexpected value '" + value + "'");
        }
    }

    public boolean hasAccess(Integer requiredRole) {
        if (requiredRole == null) {
            return true;
        }
        if (role == null) {
            return false;
        }
        return role.value <= requiredRole;
    }

    public boolean isAdmin() {
        return hasAccess(RoleEnum.ADMIN.getValue());
    }
}
