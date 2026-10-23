package ru.taskflow.app.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.taskflow.shared.security.AuthenticatedUser;

import java.io.IOException;
import java.util.List;

@Component
@Order(0)
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('EXPIRE', KEYS[1], 60) end
            return n
            """, Long.class);
    private final StringRedisTemplate redis;

    public ApiRateLimitFilter(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!request.getRequestURI().startsWith("/api/v1/") || auth == null
                || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            chain.doFilter(request, response);
            return;
        }
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        String key = "api:rate:" + user.userId() + ":" + (read ? "read:" : "write:") + System.currentTimeMillis() / 60000;
        Long count;
        try { count = redis.execute(COUNT, List.of(key)); }
        catch (RuntimeException exception) {
            reject(response, 503, "Сервис сейчас занят. Попробуйте чуть позже.", 5);
            return;
        }
        if (count == null || count > (read ? 300 : 120)) {
            reject(response, 429, "Слишком много запросов. Попробуйте через минуту.", 60);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String detail, int retryAfter) throws IOException {
        response.setStatus(status);
        response.setHeader("Retry-After", Integer.toString(retryAfter));
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
