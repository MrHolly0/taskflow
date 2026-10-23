package ru.taskflow.assistant.application;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.api.AssistantService;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantAdmissionAspectTest {

    private final AssistantRateLimiter limiter = mock(AssistantRateLimiter.class);

    @Test
    void webAndTelegramUseTheSameAdmissionPath() {
        AssistantService target = mock(AssistantService.class);
        var proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new AssistantAdmissionAspect(limiter, 1));
        AssistantService proxy = proxyFactory.getProxy();
        UUID user = UUID.randomUUID();
        when(limiter.allow(user)).thenReturn(true, false);
        proxy.handleText(user, "дело", AssistantChannel.WEB, AssistantEntryPoint.CHAT);
        assertThatThrownBy(() -> proxy.handleText(user, "дело", AssistantChannel.TELEGRAM,
                AssistantEntryPoint.CHAT, "tg:update:1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(limiter, times(2)).allow(user);
    }

    @Test
    void rejectsBusyInstanceWithoutWaitingAndReleasesAfterFailure() throws Throwable {
        var aspect = new AssistantAdmissionAspect(limiter, 1);
        ProceedingJoinPoint slow = invocation();
        ProceedingJoinPoint next = invocation();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(limiter.allow(any())).thenReturn(true);
        when(slow.proceed()).thenAnswer(ignored -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            throw new IllegalStateException("upstream failed");
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> {
                try { aspect.admit(slow); } catch (Throwable ignored) { }
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> aspect.admit(next))
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        aspect.admit(next);
        verify(next).proceed();
    }

    private ProceedingJoinPoint invocation() {
        ProceedingJoinPoint invocation = mock(ProceedingJoinPoint.class);
        when(invocation.getArgs()).thenReturn(new Object[] {UUID.randomUUID()});
        return invocation;
    }
}
