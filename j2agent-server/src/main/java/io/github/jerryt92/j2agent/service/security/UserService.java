package io.github.jerryt92.j2agent.service.security;

import io.github.jerryt92.j2agent.constants.ErrorConstants;
import io.github.jerryt92.j2agent.mapper.mgb.UserPoMapper;
import io.github.jerryt92.j2agent.mapper.ext.AuditChatContextExtMapper;
import io.github.jerryt92.j2agent.mapper.ext.LlmUsageRecordMapper;
import io.github.jerryt92.j2agent.model.RegisterRequestDto;
import io.github.jerryt92.j2agent.model.ResetPasswordRequestDto;
import io.github.jerryt92.j2agent.model.UserCreateRequestDto;
import io.github.jerryt92.j2agent.model.UserDto;
import io.github.jerryt92.j2agent.model.UserListDto;
import io.github.jerryt92.j2agent.model.UserPasswordUpdateRequestDto;
import io.github.jerryt92.j2agent.model.UserRoleUpdateRequestDto;
import io.github.jerryt92.j2agent.model.po.mgb.UserPo;
import io.github.jerryt92.j2agent.model.po.mgb.UserPoExample;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import io.github.jerryt92.j2agent.utils.UserUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 用户管理服务。
 */
@Service
public class UserService {
    @org.springframework.beans.factory.annotation.Autowired
    private ResourcePermissionCache resourcePermissionCache;
    private static final String BUILTIN_ADMIN_USERNAME = "aiadmin";
    private static final int MAX_EXTERNAL_USER_ID_LENGTH = 32;
    private static final int USERNAME_SUFFIX_LENGTH = 6;
    private static final int MAX_USERNAME_BASE_LENGTH = 57;
    private static final int MAX_USERNAME_RESOLVE_ATTEMPTS = 10;
    private static final String USERNAME_SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final UserPoMapper userPoMapper;
    private final LoginService loginService;
    private final EmailVerificationService emailVerificationService;
    private final EmailRegisterService emailRegisterService;
    private final LlmUsageRecordMapper llmUsageRecordMapper;
    private final AuditChatContextExtMapper auditChatContextExtMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserService(UserPoMapper userPoMapper,
                       LoginService loginService,
                       EmailVerificationService emailVerificationService,
                       EmailRegisterService emailRegisterService,
                       LlmUsageRecordMapper llmUsageRecordMapper,
                       AuditChatContextExtMapper auditChatContextExtMapper) {
        this.userPoMapper = userPoMapper;
        this.loginService = loginService;
        this.emailVerificationService = emailVerificationService;
        this.emailRegisterService = emailRegisterService;
        this.llmUsageRecordMapper = llmUsageRecordMapper;
        this.auditChatContextExtMapper = auditChatContextExtMapper;
    }

    /**
     * 查询所有用户。
     */
    public UserListDto listUsers() {
        return listUsers(false);
    }

    /**
     * 审计筛选按面板数据源独立返回用户。不得把 Token 与聊天会话用户并集返回，
     * 否则会在另一面板出现没有任何结果的无效筛选项。
     */
    public UserListDto listAuditUsers(String source) {
        Set<String> auditUserIds = loadAuditUserIds(source);
        List<UserDto> users = new ArrayList<>();
        Set<String> existingIds = new LinkedHashSet<>();
        for (UserDto user : listUsers(true).getData()) {
            if (user != null && !isBlank(user.getUserId())
                    && auditUserIds.contains(user.getUserId().trim())) {
                users.add(user);
                existingIds.add(user.getUserId().trim());
            }
        }
        auditUserIds.stream()
                .filter(id -> !existingIds.contains(id))
                .sorted()
                .forEach(id -> {
                    UserDto deleted = new UserDto();
                    deleted.setUserId(id);
                    deleted.setRole(UserRoleEnum.USER.getValue());
                    deleted.setDeleted(true);
                    users.add(deleted);
                });
        UserListDto dto = new UserListDto();
        dto.setData(users);
        return dto;
    }

    private Set<String> loadAuditUserIds(String source) {
        List<String> rawIds;
        if ("token".equals(source)) {
            rawIds = llmUsageRecordMapper.selectDistinctUserIds();
        } else if ("context".equals(source)) {
            rawIds = auditChatContextExtMapper.selectDistinctUserIds();
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "audit user source must be token or context");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String rawId : rawIds) {
            if (!isBlank(rawId)) {
                ids.add(rawId.trim());
            }
        }
        return ids;
    }

    private UserListDto listUsers(boolean includeApiUsers) {
        UserPoExample example = new UserPoExample();
        example.setOrderByClause("create_time DESC, username ASC");
        List<UserDto> users = userPoMapper.selectByExample(example).stream()
                .filter(user -> includeApiUsers || !"API".equals(user.getAccountType()))
                .map(this::toDto)
                .toList();
        UserListDto dto = new UserListDto();
        dto.setData(users);
        return dto;
    }

    /**
     * 创建用户，当前模型仅允许管理员创建用户。
     */
    public UserDto createUser(UserCreateRequestDto request) {
        requireAdmin();
        if (request == null || isBlank(request.getUsername()) || isBlank(request.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username and password are required");
        }
        int role = normalizeRole(request.getRole());
        ensureUsernameAvailable(request.getUsername());

        UserPo user = new UserPo();
        user.setId(UUIDv7Utils.randomUUIDv7());
        user.setUsername(request.getUsername());
        user.setCreateTime(System.currentTimeMillis());
        user.setRole(role);
        user.setPasswordHash(UserUtil.getPasswordHash(user.getId(), request.getPassword()));
        userPoMapper.insertSelective(user);
        return toDto(user);
    }

    /**
     * 删除普通用户，内置管理员不可删除。
     */
    public void deleteUser(String userId) {
        requireAdmin();
        UserPo user = requireUser(userId);
        ensureNotApiUser(user);
        ensureMutableUser(user);
        resourcePermissionCache.mutate(userId, () -> userPoMapper.deleteByPrimaryKey(userId));
        loginService.invalidateUserLogin(userId);
    }

    /**
     * 更新用户角色，保护内置管理员不被降权。
     */
    public void updateUserRole(UserRoleUpdateRequestDto request) {
        requireAdmin();
        if (request == null || isBlank(request.getUserId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        UserPo user = requireUser(request.getUserId());
        ensureNotApiUser(user);
        ensureMutableUser(user);
        user.setRole(normalizeRole(request.getRole()));
        userPoMapper.updateByPrimaryKeySelective(user);
        loginService.invalidateUserLogin(user.getId());
    }

    /**
     * 按规范化邮箱查找用户，不存在时返回 null。
     */
    public UserPo findByEmail(String email) {
        if (isBlank(email)) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        UserPoExample example = new UserPoExample();
        example.createCriteria().andEmailEqualTo(normalized);
        List<UserPo> users = userPoMapper.selectByExample(example);
        return users.isEmpty() ? null : users.getFirst();
    }

    /**
     * 邮箱找回密码：校验验证码后更新密码并失效会话。
     */
    public void resetPasswordByEmail(ResetPasswordRequestDto request) {
        if (request == null || isBlank(request.getEmail())
                || isBlank(request.getNewPassword()) || isBlank(request.getCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.RESET_PASSWORD_FIELDS_REQUIRED);
        }
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        emailVerificationService.verifyAndConsumeReset(email, request.getCode());
        UserPo user = findByEmail(email);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.RESET_PASSWORD_CODE_INVALID);
        }
        ensureMutableUser(user);
        user.setPasswordHash(UserUtil.getPasswordHash(user.getId(), request.getNewPassword()));
        userPoMapper.updateByPrimaryKeySelective(user);
        loginService.invalidateUserLogin(user.getId());
    }

    /**
     * 外部系统合法 JWT 首次访问时自动建档：id 沿用 JWT user claim，username 来自 name 并去重。
     */
    public UserPo provisionExternalUser(String externalUserId, String preferredUsername) {
        String userId = normalizeExternalUserId(externalUserId);
        UserPo existing = userPoMapper.selectByPrimaryKey(userId);
        if (existing != null) {
            return existing;
        }
        String username = resolveUniqueUsername(preferredUsername, userId);
        UserPo user = new UserPo();
        user.setId(userId);
        user.setUsername(username);
        user.setRole(UserRoleEnum.USER.getValue());
        user.setCreateTime(System.currentTimeMillis());
        user.setPasswordHash(UserUtil.getPasswordHash(userId, UUID.randomUUID().toString()));
        try {
            userPoMapper.insertSelective(user);
        } catch (DuplicateKeyException ex) {
            UserPo raced = userPoMapper.selectByPrimaryKey(userId);
            if (raced != null) {
                return raced;
            }
            throw ex;
        }
        return user;
    }

    /**
     * 邮箱自助注册：校验验证码后创建普通用户，未传用户名时以邮箱作为登录名。
     */
    public void registerByEmail(RegisterRequestDto request) {
        if (request == null || isBlank(request.getPassword())
                || isBlank(request.getEmail()) || isBlank(request.getCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.REGISTER_FIELDS_REQUIRED);
        }
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        emailRegisterService.requireEmailAllowed(email);
        String username = isBlank(request.getUsername()) ? email : request.getUsername().trim();
        emailVerificationService.verifyAndConsume(email, request.getCode());
        ensureUsernameAvailable(username);
        ensureEmailAvailable(email);

        UserPo user = new UserPo();
        user.setId(UUIDv7Utils.randomUUIDv7());
        user.setUsername(username);
        user.setEmail(email);
        user.setCreateTime(System.currentTimeMillis());
        user.setRole(UserRoleEnum.USER.getValue());
        user.setPasswordHash(UserUtil.getPasswordHash(user.getId(), request.getPassword()));
        userPoMapper.insertSelective(user);
    }

    /**
     * 管理员可重置他人密码，普通用户仅可修改自己的密码。
     */
    public void updateUserPassword(UserPasswordUpdateRequestDto request) {
        if (request == null || isBlank(request.getNewPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newPassword is required");
        }
        UserContextBo session = requireSession();
        String targetUserId = isBlank(request.getUserId()) ? session.getUserId() : request.getUserId();
        UserPo user = requireUser(targetUserId);
        ensureNotApiUser(user);

        if (!session.isAdmin()) {
            if (!session.getUserId().equals(targetUserId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cannot modify another user's password");
            }
        } else if (!session.getUserId().equals(targetUserId)) {
            ensureMutableUser(user);
        }

        user.setPasswordHash(UserUtil.getPasswordHash(user.getId(), request.getNewPassword()));
        userPoMapper.updateByPrimaryKeySelective(user);
        loginService.invalidateUserLogin(user.getId());
    }

    private UserContextBo requireSession() {
        UserContextBo session = loginService.getSession();
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login required");
        }
        return session;
    }

    private void requireAdmin() {
        if (!requireSession().isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin required");
        }
    }

    private UserPo requireUser(String userId) {
        if (isBlank(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        UserPo user = userPoMapper.selectByPrimaryKey(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
        return user;
    }

    private void ensureUsernameAvailable(String username) {
        if (isUsernameTaken(username)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.REGISTER_USERNAME_EXISTS);
        }
    }

    private String normalizeExternalUserId(String externalUserId) {
        if (isBlank(externalUserId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid external user id");
        }
        String userId = externalUserId.trim();
        if (userId.length() > MAX_EXTERNAL_USER_ID_LENGTH) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid external user id");
        }
        return userId;
    }

    private String resolveUniqueUsername(String preferredUsername, String userId) {
        String base = isBlank(preferredUsername) ? userId : preferredUsername.trim();
        if (base.length() > MAX_USERNAME_BASE_LENGTH) {
            base = base.substring(0, MAX_USERNAME_BASE_LENGTH);
        }
        if (!isUsernameTaken(base)) {
            return base;
        }
        for (int attempt = 0; attempt < MAX_USERNAME_RESOLVE_ATTEMPTS; attempt++) {
            String candidate = base + "_" + randomAlphanumericSuffix(USERNAME_SUFFIX_LENGTH);
            if (!isUsernameTaken(candidate)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "failed to resolve unique username");
    }

    private boolean isUsernameTaken(String username) {
        UserPoExample example = new UserPoExample();
        example.createCriteria().andUsernameEqualTo(username);
        return userPoMapper.countByExample(example) > 0;
    }

    private String randomAlphanumericSuffix(int length) {
        StringBuilder suffix = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            suffix.append(USERNAME_SUFFIX_ALPHABET.charAt(
                    secureRandom.nextInt(USERNAME_SUFFIX_ALPHABET.length())));
        }
        return suffix.toString();
    }

    private void ensureEmailAvailable(String email) {
        UserPoExample example = new UserPoExample();
        example.createCriteria().andEmailEqualTo(email);
        if (userPoMapper.countByExample(example) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.REGISTER_EMAIL_EXISTS);
        }
    }

    private void ensureMutableUser(UserPo user) {
        if (BUILTIN_ADMIN_USERNAME.equals(user.getUsername())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "builtin admin cannot be modified");
        }
    }

    private void ensureNotApiUser(UserPo user) {
        if ("API".equals(user.getAccountType())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "api-only users are managed by api key management");
        }
    }

    private int normalizeRole(Integer role) {
        int value = role == null ? UserRoleEnum.USER.getValue() : role;
        try {
            UserRoleEnum.fromValue(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported role");
        }
        return value;
    }

    private UserDto toDto(UserPo user) {
        UserDto dto = new UserDto();
        dto.setUserId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setRole(user.getRole());
        dto.setCreateTime(user.getCreateTime());
        dto.setEmail(user.getEmail());
        dto.setDeleted(false);
        return dto;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
