package io.github.jerryt92.j2agent.model.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 用户角色，数值越小权限越高。
 */
public enum UserRoleEnum {
    /** 系统管理员 */
    ADMIN(1),
    /** 知识库管理员：可维护知识库并为普通用户分配库级授权 */
    KNOWLEDGE_ADMIN(2),
    /** 普通用户 */
    USER(3);

    private final Integer value;

    UserRoleEnum(Integer value) {
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
    public static UserRoleEnum fromValue(Integer value) {
        for (UserRoleEnum role : UserRoleEnum.values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
}

