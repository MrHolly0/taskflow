package ru.taskflow.assistant.application;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.Semaphore;

@Aspect
@Component
@Order(10)
public class AssistantAdmissionAspect {

    private final AssistantRateLimiter rateLimiter;
    private final Semaphore permits;
    private final ThreadLocal<Boolean> admitted = ThreadLocal.withInitial(() -> false);

    public AssistantAdmissionAspect(AssistantRateLimiter rateLimiter,
            @Value("${app.assistant.max-concurrent-requests:4}") int maxConcurrentRequests) {
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("max-concurrent-requests must be positive");
        }
        this.rateLimiter = rateLimiter;
        this.permits = new Semaphore(maxConcurrentRequests);
    }

    @Around("execution(* ru.taskflow.assistant.api.AssistantService+.handleText(..)) || "
            + "execution(* ru.taskflow.assistant.api.AssistantService+.handleVoice(..))")
    public Object admit(ProceedingJoinPoint invocation) throws Throwable {
        if (admitted.get()) return invocation.proceed();
        UUID userId = (UUID) invocation.getArgs()[0];
        if (!permits.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ассистент сейчас занят, попробуйте чуть позже");
        }
        try {
            if (!rateLimiter.allow(userId)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "слишком много обращений, попробуйте через минуту");
            }
            admitted.set(true);
            return invocation.proceed();
        } finally {
            admitted.remove();
            permits.release();
        }
    }
}
