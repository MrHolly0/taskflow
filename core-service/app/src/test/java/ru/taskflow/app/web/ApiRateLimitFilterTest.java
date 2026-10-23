package ru.taskflow.app.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.taskflow.shared.security.AuthenticatedUser;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiRateLimitFilterTest {
    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void separatesReadAndWriteBudgetsByAuthenticatedUser() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(121L);
        var filter = new ApiRateLimitFilter(redis);
        var user = new AuthenticatedUser(UUID.randomUUID(), "rate-test");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        var write = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/tasks/quick"), write, new MockFilterChain());
        assertThat(write.getStatus()).isEqualTo(429);
        assertThat(write.getHeader("Retry-After")).isEqualTo("60");
        var chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/tasks/view"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
        verify(redis).execute(any(RedisScript.class), argThat(keys -> keys.size() == 1 && keys.getFirst().toString().contains(user.userId() + ":write:")));
        verify(redis).execute(any(RedisScript.class), argThat(keys -> keys.size() == 1 && keys.getFirst().toString().contains(user.userId() + ":read:")));
    }

    @Test
    void unavailableRedisRejectsWithoutExecutingMutation() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenThrow(new IllegalStateException("offline"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(UUID.randomUUID(), "rate-test"), null, List.of()));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new ApiRateLimitFilter(redis).doFilter(new MockHttpServletRequest("POST", "/api/v1/tasks/quick"), response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void healthAndUnauthenticatedRequestsDoNotConsumeUserBudget() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        var filter = new ApiRateLimitFilter(redis);
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/tasks/view"), new MockHttpServletResponse(), new MockFilterChain());
        verifyNoInteractions(redis);
    }
}
