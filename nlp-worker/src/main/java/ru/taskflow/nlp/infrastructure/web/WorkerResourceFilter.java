package ru.taskflow.nlp.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.taskflow.shared.resources.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkerResourceFilter extends OncePerRequestFilter {
    private final ResourcePolicy policy; private final RedisResourceGate gate; private final ObjectMapper json;
    public WorkerResourceFilter(ResourcePolicy policy,RedisResourceGate gate,ObjectMapper json) {
        this.policy=policy;this.gate=gate;this.json=json;
        if(policy.getInternalSecret().length()<32)
            throw new IllegalStateException("NLP_INTERNAL_SECRET должен содержать не меньше 32 символов");
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {return !request.getRequestURI().startsWith("/nlp/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        try {
            String secret=request.getHeader("X-Model-Secret");
            if(secret==null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                    policy.getInternalSecret().getBytes(StandardCharsets.UTF_8)))
                throw new ResourceRejectedException(HttpStatus.FORBIDDEN,"Неверные внутренние учётные данные");
            UUID user;long deadline;
            try { user=UUID.fromString(request.getHeader("X-Model-User"));
                deadline=Long.parseLong(request.getHeader("X-Model-Deadline"));
            } catch(RuntimeException invalid) { throw new ResourceRejectedException(HttpStatus.BAD_REQUEST,"Нет контекста обработки"); }
            var context=new ModelRequestContext(user,Math.min(deadline,System.currentTimeMillis()+policy.getOperationTimeoutMs()),0);
            context.remainingMillis();
            try(var scope=ModelRequestContext.open(context);
                var lease=gate.acquire("worker",user,policy.getWorkerConcurrent(),policy.getUserConcurrent())) {
                chain.doFilter(request,response);
            }
        } catch(ResourceRejectedException exception) {
            response.setStatus(exception.getStatusCode().value());response.setContentType("application/problem+json");
            exception.getHeaders().forEach((name,values)->response.setHeader(name,values.getFirst()));
            json.writeValue(response.getOutputStream(),ProblemDetail.forStatusAndDetail(exception.getStatusCode(),exception.getReason()));
        }
    }
}
