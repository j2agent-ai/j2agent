package io.github.jerryt92.j2agent.service.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.github.jerryt92.j2agent.jwt.JwtService;
import io.github.jerryt92.j2agent.jwt.TerminalType;
import io.github.jerryt92.j2agent.mapper.mgb.UserPoMapper;
import io.github.jerryt92.j2agent.model.AuthResultDto;
import io.github.jerryt92.j2agent.model.po.mgb.UserPo;
import io.github.jerryt92.j2agent.model.po.mgb.UserPoExample;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.model.security.UserRoleEnum;
import io.github.jerryt92.j2agent.utils.I18nLocaleUtils;
import io.github.jerryt92.j2agent.utils.UserUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class LoginService {
    @org.springframework.beans.factory.annotation.Autowired
    private PermissionWarmup permissionWarmup;
    public static final String LOGIN_ATTRIBUTE = "login";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_PC = "auth-pc";

    private final UserPoMapper userPoMapper;
    private final CaptchaService captchaService;
    private final JwtService jwtService;
    private final UserLoginContextCache userLoginContextCache;
    private final UserService userService;
    private final ApiAccessKeyService apiAccessKeyService;
    private final ThreadLocal<UserContextBo> sessionThreadLocal = new ThreadLocal<>();

    public LoginService(
            UserPoMapper userPoMapper,
            CaptchaService captchaService,
            JwtService jwtService,
            UserLoginContextCache userLoginContextCache,
            @Lazy UserService userService,
            ApiAccessKeyService apiAccessKeyService) {
        this.userPoMapper = userPoMapper;
        this.captchaService = captchaService;
        this.jwtService = jwtService;
        this.userLoginContextCache = userLoginContextCache;
        this.userService = userService;
        this.apiAccessKeyService = apiAccessKeyService;
    }

    public AuthResultDto login(String username, String password, String captchaCode, String hash) {
        UserPoExample example = new UserPoExample();
        example.createCriteria().andUsernameEqualTo(username);
        if (!captchaService.verifyCaptchaCode(captchaCode, hash)) {
            return null;
        }
        List<UserPo> userPos = userPoMapper.selectByExample(example);
        if (userPos.isEmpty()) {
            return null;
        }
        UserPo userPo = userPos.get(0);
        if (!apiAccessKeyService.isPasswordLoginAllowed(userPo.getId())) {
            return null;
        }
        if (!UserUtil.verifyPassword(userPo.getId(), password, userPo.getPasswordHash())) {
            return null;
        }
        return makeToken(userPo);
    }

    public boolean resolveRequest(HttpServletRequest request) {
        boolean resolved;
        String authorization = request == null ? null : request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String credential = authorization.substring(BEARER_PREFIX.length()).trim();
            resolved = credential.startsWith(ApiAccessKeyService.KEY_PREFIX)
                    ? resolveApiKey(credential) : resolveToken(credential);
        } else {
            // WebSocket 浏览器客户端无法设置 Authorization 请求头，保留 authorization 查询参数传递 JWT。
            String credential = request == null ? null : request.getParameter("authorization");
            resolved = credential != null && (credential.startsWith(ApiAccessKeyService.KEY_PREFIX)
                    ? resolveApiKey(credential) : resolveToken(credential));
        }
        if (resolved) {
            UserContextBo userContextBo = getSession();
            if (userContextBo != null) {
                userContextBo.setLanguage(I18nLocaleUtils.resolveRequestLanguage(request));
            }
        }
        return resolved;
    }

    public boolean resolveToken(String token) {
        if (StringUtils.isBlank(token)) {
            return false;
        }
        try {
            UserContextBo userContextBo = resolveUserContext(jwtService.verifyToken(token));
            if (userContextBo == null) {
                return false;
            }
            bindSession(userContextBo);
            return true;
        } catch (JWTVerificationException e) {
            return false;
        }
    }

    private boolean resolveApiKey(String rawKey) {
        UserContextBo userContextBo = apiAccessKeyService.resolve(rawKey);
        if (userContextBo == null) return false;
        bindSession(userContextBo);
        return true;
    }

    public UserContextBo getSession() {
        return sessionThreadLocal.get();
    }

    public void bindSession(UserContextBo userContextBo) {
        this.sessionThreadLocal.set(userContextBo);
    }

    public void clearSession() {
        this.sessionThreadLocal.remove();
    }

    public void logout(HttpServletRequest request) {
        String token = extractToken(request);
        if (StringUtils.isNotBlank(token)) {
            try {
                DecodedJWT decoded = jwtService.verifyToken(token);
                String sid = decoded.getClaim("sid").asString();
                String userId = decoded.getClaim("user").asString();
                userLoginContextCache.remove(sid, userId);
            } catch (JWTVerificationException ignored) {
                // token 无效，无需清理缓存
            }
        }
        clearSession();
    }

    public void invalidateUserLogin(String userId) {
        userLoginContextCache.invalidateByUserId(userId);
    }

    public static String extractToken(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return request.getParameter("authorization");
    }

    private AuthResultDto makeToken(UserPo userPo) {
        Instant now = Instant.now();
        long expiresIn = jwtService.expiresTime(TerminalType.BROWSER, false);
        Instant expiresAt = now.plus(expiresIn, ChronoUnit.SECONDS);
        UserRoleEnum role = UserRoleEnum.fromValue(userPo.getRole());
        String sid = UUID.randomUUID().toString();

        String token = jwtService.signToken(JWT.create()
                .withExpiresAt(expiresAt)
                .withClaim("terminal", TerminalType.BROWSER.name())
                .withClaim("rememberMe", false)
                .withClaim("user", userPo.getId())
                .withClaim("name", userPo.getUsername())
                .withClaim("classify", "system")
                .withArrayClaim("auth", new String[]{AUTH_PC})
                .withClaim("sid", sid));

        UserContextBo userContextBo = new UserContextBo();
        userContextBo.setSessionId(sid);
        userContextBo.setUserId(userPo.getId());
        userContextBo.setUsername(userPo.getUsername());
        userContextBo.setRole(role);
        userContextBo.setPermissions(List.of());
        userContextBo.setExpireTime(expiresAt.toEpochMilli());
        userLoginContextCache.save(sid, userPo.getId(), userContextBo, expiresIn);
        if (permissionWarmup != null) permissionWarmup.warm(userPo.getId());

        AuthResultDto dto = new AuthResultDto();
        dto.setToken(token);
        dto.setExpiresIn(expiresIn);
        return dto;
    }

    private UserContextBo resolveUserContext(DecodedJWT decoded) {
        String userId = decoded.getClaim("user").asString();
        String username = decoded.getClaim("name").asString();
        String sid = decoded.getClaim("sid").asString();
        if (StringUtils.isBlank(userId) && StringUtils.isBlank(username)) {
            return null;
        }
        long expireTime = decoded.getExpiresAt() != null ? decoded.getExpiresAt().getTime() : 0L;
        long remainingTtl = remainingTtlSeconds(decoded);
        if (remainingTtl <= 0) {
            return null;
        }

        Optional<UserContextBo> cached = userLoginContextCache.get(sid);
        if (cached.isPresent()) {
            UserContextBo userContextBo = cached.get();
            if (StringUtils.isNotBlank(userId) && !userId.equals(userContextBo.getUserId())) {
                userLoginContextCache.remove(sid, userContextBo.getUserId());
                return loadAndCacheContext(sid, userId, username, expireTime, remainingTtl);
            }
            userContextBo.setExpireTime(expireTime);
            return userContextBo;
        }
        return loadAndCacheContext(sid, userId, username, expireTime, remainingTtl);
    }

    private UserContextBo loadAndCacheContext(
            String sid, String userId, String username, long expireTime, long ttlSeconds) {
        if (StringUtils.isBlank(userId)) {
            return null;
        }
        UserPo userPo = userPoMapper.selectByPrimaryKey(userId);
        if (userPo == null) {
            try {
                userPo = userService.provisionExternalUser(userId, username);
            } catch (ResponseStatusException ex) {
                return null;
            }
        }
        if (apiAccessKeyService.isApiUser(userPo.getId())) {
            return null;
        }
        if (userPo == null) {
            return null;
        }

        UserContextBo userContextBo = new UserContextBo();
        userContextBo.setSessionId(sid);
        userContextBo.setUserId(userPo.getId());
        userContextBo.setUsername(userPo.getUsername());
        userContextBo.setRole(UserRoleEnum.fromValue(userPo.getRole()));
        userContextBo.setPermissions(List.of());
        userContextBo.setExpireTime(expireTime);
        userLoginContextCache.save(sid, userPo.getId(), userContextBo, ttlSeconds);
        if (permissionWarmup != null) permissionWarmup.warm(userPo.getId());
        return userContextBo;
    }

    private long remainingTtlSeconds(DecodedJWT decoded) {
        if (decoded.getExpiresAt() == null) {
            return 0;
        }
        long remaining = (decoded.getExpiresAt().getTime() - System.currentTimeMillis()) / 1000;
        return Math.max(remaining, 1);
    }
}
