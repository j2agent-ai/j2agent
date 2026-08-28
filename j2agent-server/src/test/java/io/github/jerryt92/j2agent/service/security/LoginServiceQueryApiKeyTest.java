package io.github.jerryt92.j2agent.service.security;

import io.github.jerryt92.j2agent.jwt.JwtService;
import io.github.jerryt92.j2agent.mapper.mgb.UserPoMapper;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginServiceQueryApiKeyTest {

    @Test
    void resolvesApiKeyFromAuthorizationQueryParameter() {
        ApiAccessKeyService apiAccessKeyService = mock(ApiAccessKeyService.class);
        String apiKey = "apikey-test-id.test-secret";
        when(apiAccessKeyService.resolve(apiKey)).thenReturn(new UserContextBo());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("authorization")).thenReturn(apiKey);

        LoginService service = new LoginService(
                mock(UserPoMapper.class),
                mock(CaptchaService.class),
                mock(JwtService.class),
                mock(UserLoginContextCache.class),
                mock(UserService.class),
                apiAccessKeyService);

        assertTrue(service.resolveRequest(request));
        verify(apiAccessKeyService).resolve(apiKey);
    }
}
