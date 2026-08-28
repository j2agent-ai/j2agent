package io.github.jerryt92.j2agent.service.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.mapper.ApiAccessKeyMapper;
import io.github.jerryt92.j2agent.mapper.mgb.UserPoMapper;
import io.github.jerryt92.j2agent.model.po.mgb.UserPo;
import io.github.jerryt92.j2agent.model.po.mgb.UserPoExample;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import io.github.jerryt92.j2agent.utils.UserUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/** 长期 API Key 的创建、校验与 Redis 滑动缓存。 */
@Service
public class ApiAccessKeyService {
    public static final String KEY_PREFIX = "apikey-";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private final ApiAccessKeyMapper mapper;
    private final UserPoMapper userPoMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String cachePrefix;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiAccessKeyService(ApiAccessKeyMapper mapper, UserPoMapper userPoMapper,
                               StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                               RedisKeyNamespaces namespaces) {
        this.mapper = mapper;
        this.userPoMapper = userPoMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cachePrefix = namespaces.key("api-key:ctx:");
    }

    @Transactional
    public CreatedKey create(String keyName, String username, Integer role) {
        if (blank(keyName) || blank(username)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyName and username are required");
        }
        UserPoExample example = new UserPoExample();
        example.createCriteria().andUsernameEqualTo(username.trim());
        if (userPoMapper.countByExample(example) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username already exists");
        }
        int normalizedRole = normalizeRole(role);
        String userId = UUIDv7Utils.randomUUIDv7();
        UserPo user = new UserPo();
        user.setId(userId);
        user.setUsername(username.trim());
        user.setRole(normalizedRole);
        user.setCreateTime(System.currentTimeMillis());
        user.setPasswordHash(UserUtil.getPasswordHash(userId, randomSecret()));
        // API 专用账户：禁止密码登录，与普通 HUMAN 用户隔离
        user.setAccountType("API");
        user.setPasswordLoginEnabled(false);
        userPoMapper.insertSelective(user);

        String id = userId;
        String prefix = KEY_PREFIX + id;
        String raw = prefix + "." + randomSecret();
        long now = System.currentTimeMillis();
        mapper.insert(new ApiAccessKeyMapper.KeyRow(id, userId, keyName.trim(), prefix, hash(raw), now,
                null, user.getUsername(), normalizedRole, "API", false));
        return new CreatedKey(toDto(mapper.findById(id)), raw);
    }

    public UserContextBo resolve(String rawKey) {
        ParsedKey parsed = parse(rawKey);
        if (parsed == null) return null;
        // 缓存键包含完整 Key 的 HMAC，不能仅按公开 id 命中，否则伪造同 id 的 Key 会绕过密钥校验。
        String keyHash = hash(rawKey);
        UserContextBo cached = readCached(keyHash);
        if (cached != null) return cached;
        ApiAccessKeyMapper.KeyRow row = mapper.findByKeyPrefix(parsed.prefix());
        if (row == null || !"API".equals(row.accountType()) || !MessageDigest.isEqual(
                keyHash.getBytes(StandardCharsets.US_ASCII), row.secretHash().getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        UserContextBo context = context(row);
        cache(keyHash, context);
        mapper.updateLastUsedTime(row.id(), System.currentTimeMillis());
        return context;
    }

    public List<KeyDto> list() {
        return mapper.list().stream().map(this::toDto).toList();
    }

    @Transactional
    public void updateRole(String id, Integer role) {
        ApiAccessKeyMapper.KeyRow row = requireKey(id);
        if (mapper.updateApiUserRole(trimId(row.userId()), normalizeRole(role)) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "api user not found");
        }
        evict(row.secretHash());
    }

    @Transactional
    public void delete(String id) {
        ApiAccessKeyMapper.KeyRow row = requireKey(id);
        mapper.deleteApiUser(trimId(row.userId())); // FK ON DELETE CASCADE removes the key.
        evict(row.secretHash());
    }

    public boolean isApiUser(String userId) {
        return userId != null && mapper.isApiUser(userId.trim());
    }

    public boolean isPasswordLoginAllowed(String userId) {
        if (userId == null) {
            return false;
        }
        Boolean enabled = mapper.isPasswordLoginEnabled(userId.trim());
        return Boolean.TRUE.equals(enabled);
    }

    private UserContextBo readCached(String id) {
        try {
            String json = redisTemplate.opsForValue().get(cachePrefix + id);
            if (json == null || json.isBlank()) return null;
            UserContextBo context = objectMapper.readValue(json, UserContextBo.class);
            // 旧缓存可能含 char(32) 尾部空格，读出后统一 trim，避免多轮会话归属校验失败
            normalizeIds(context);
            redisTemplate.expire(cachePrefix + id, CACHE_TTL);
            return context;
        } catch (Exception ignored) { return null; }
    }

    private void cache(String id, UserContextBo context) {
        try { redisTemplate.opsForValue().set(cachePrefix + id, objectMapper.writeValueAsString(context), CACHE_TTL); }
        catch (JsonProcessingException ignored) { }
    }

    private void evict(String id) { redisTemplate.delete(cachePrefix + id); }

    private ApiAccessKeyMapper.KeyRow requireKey(String id) {
        ApiAccessKeyMapper.KeyRow row = mapper.findById(id);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "api key not found");
        return row;
    }

    private UserContextBo context(ApiAccessKeyMapper.KeyRow row) {
        UserContextBo context = new UserContextBo();
        // char(32) 主键读出可能带尾部空格；会话与 chat_context_record 落库（setUserId 会 trim）必须一致
        String keyId = trimId(row.id());
        context.setSessionId(keyId);
        context.setApiKeyId(keyId);
        context.setCredentialType("API_KEY");
        context.setUserId(trimId(row.userId()));
        context.setUsername(row.username());
        context.setRole(UserRoleEnum.fromValue(row.role()));
        context.setExpireTime(0L);
        return context;
    }

    private KeyDto toDto(ApiAccessKeyMapper.KeyRow row) {
        return new KeyDto(trimId(row.id()), row.keyName(), trimId(row.userId()), row.username(), row.role(),
                row.keyPrefix() + "****", row.createTime(), row.lastUsedTime());
    }

    private static void normalizeIds(UserContextBo context) {
        if (context == null) {
            return;
        }
        if (context.getUserId() != null) {
            context.setUserId(context.getUserId().trim());
        }
        if (context.getSessionId() != null) {
            context.setSessionId(context.getSessionId().trim());
        }
        if (context.getApiKeyId() != null) {
            context.setApiKeyId(context.getApiKeyId().trim());
        }
    }

    private static String trimId(String value) {
        return value == null ? null : value.trim();
    }

    private ParsedKey parse(String value) {
        if (value == null || !value.startsWith(KEY_PREFIX)) return null;
        int dot = value.indexOf('.');
        if (dot <= KEY_PREFIX.length() || dot == value.length() - 1) return null;
        String prefix = value.substring(0, dot);
        String id = prefix.substring(KEY_PREFIX.length());
        return id.isBlank() ? null : new ParsedKey(id, prefix);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("cannot hash api key", e); }
    }

    private String randomSecret() {
        byte[] bytes = new byte[32]; secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private int normalizeRole(Integer role) {
        int value = role == null ? UserRoleEnum.USER.getValue() : role;
        try { UserRoleEnum.fromValue(value); return value; }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported role"); }
    }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private record ParsedKey(String id, String prefix) { }
    public record KeyDto(String id, String keyName, String userId, String username, Integer role,
                         String maskedKey, Long createTime, Long lastUsedTime) { }
    public record CreatedKey(KeyDto key, String apiKey) { }
}
