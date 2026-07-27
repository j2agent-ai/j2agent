package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.model.UserCreateRequestDto;
import io.github.jerryt92.j2agent.model.UserDto;
import io.github.jerryt92.j2agent.model.UserListDto;
import io.github.jerryt92.j2agent.model.UserPasswordUpdateRequestDto;
import io.github.jerryt92.j2agent.model.UserRoleUpdateRequestDto;
import io.github.jerryt92.j2agent.server.api.UserApi;
import io.github.jerryt92.j2agent.service.security.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理控制器。
 */
@RestController
public class UserController implements UserApi {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 获取用户列表。
     */
    @Override
    @RequiredRole(UserRoleEnum.ADMIN)
    public ResponseEntity<UserListDto> getUsers() {
        return ResponseEntity.ok(userService.listUsers());
    }

    /**
     * 创建用户。
     */
    @Override
    @RequiredRole(UserRoleEnum.ADMIN)
    public ResponseEntity<UserDto> createUser(UserCreateRequestDto userCreateRequestDto) {
        return ResponseEntity.ok(userService.createUser(userCreateRequestDto));
    }

    /**
     * 删除用户。
     */
    @Override
    @RequiredRole(UserRoleEnum.ADMIN)
    public ResponseEntity<Void> deleteUser(String userId) {
        userService.deleteUser(userId);
        return ResponseEntity.ok().build();
    }

    /**
     * 修改用户角色。
     */
    @Override
    @RequiredRole(UserRoleEnum.ADMIN)
    public ResponseEntity<Void> updateUserRole(UserRoleUpdateRequestDto userRoleUpdateRequestDto) {
        userService.updateUserRole(userRoleUpdateRequestDto);
        return ResponseEntity.ok().build();
    }

    /**
     * 修改用户密码。
     */
    @Override
    @RequiredRole(UserRoleEnum.USER)
    public ResponseEntity<Void> updateUserPassword(UserPasswordUpdateRequestDto userPasswordUpdateRequestDto) {
        userService.updateUserPassword(userPasswordUpdateRequestDto);
        return ResponseEntity.ok().build();
    }
}
