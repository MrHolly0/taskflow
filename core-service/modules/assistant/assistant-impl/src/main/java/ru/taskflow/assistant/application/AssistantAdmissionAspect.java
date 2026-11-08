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
    private final ru.taskflow.shared.resources.ResourcePolicy policy;
    private final ThreadLocal<Boolean> admitted = ThreadLocal.withInitial(() -> false);

    public AssistantAdmissionAspect(AssistantRateLimiter rateLimiter, int maxConcurrentRequests) {
        this(rateLimiter,maxConcurrentRequests,new ru.taskflow.shared.resources.ResourcePolicy());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AssistantAdmissionAspect(AssistantRateLimiter rateLimiter,
            @Value("${app.assistant.max-concurrent-requests:4}") int maxConcurrentRequests,
            ru.taskflow.shared.resources.ResourcePolicy policy) {
        this.policy=policy;
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("max-concurrent-requests must be positive");
        }
        this.rateLimiter = rateLimiter;
        this.permits = new Semaphore(maxConcurrentRequests);
    }

    @Around("execution(* ru.taskflow.assistant.api.AssistantService+.handleText(..)) || "
            + "execution(* ru.taskflow.assistant.api.AssistantService+.handleVoice(..)) || "
            + "execution(* ru.taskflow.assistant.api.AssistantService+.handleVoiceUpload(..))")
    public Object admit(ProceedingJoinPoint invocation) throws Throwable {
        if (admitted.get()) return invocation.proceed();
        UUID userId = (UUID) invocation.getArgs()[0];
        Object[] args=invocation.getArgs();
        if(args.length>1 && args[1] instanceof String text && text.length()>policy.getMaxTextChars())
            throw new ru.taskflow.shared.resources.ResourceRejectedException(HttpStatus.PAYLOAD_TOO_LARGE,"Слишком длинное сообщение");
        if(args.length>1 && args[1] instanceof byte[] audio && audio.length>16_000_000)
            throw new ru.taskflow.shared.resources.ResourceRejectedException(HttpStatus.PAYLOAD_TOO_LARGE,"Слишком большое аудио");
        if (!permits.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ассистент сейчас занят, попробуйте чуть позже");
        }
        try (var context=ru.taskflow.shared.resources.ModelRequestContext.open(
                new ru.taskflow.shared.resources.ModelRequestContext(userId,
                        System.currentTimeMillis()+policy.getOperationTimeoutMs(),0));
             var lease=rateLimiter.enter(userId)) {
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
