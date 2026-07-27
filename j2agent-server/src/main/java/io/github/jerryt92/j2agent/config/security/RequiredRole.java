package io.github.jerryt92.j2agent.config.security;

import io.github.jerryt92.j2agent.model.security.UserRoleEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口需要的最低用户角色，角色值越小权限越高。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiredRole {
    /**
     * 角色值越小权限越高。
     */
    UserRoleEnum value() default UserRoleEnum.USER;

}
