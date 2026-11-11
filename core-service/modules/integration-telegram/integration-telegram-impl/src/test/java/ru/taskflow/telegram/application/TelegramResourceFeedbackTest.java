package ru.taskflow.telegram.application;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class TelegramResourceFeedbackTest {
    @Test void quotaFailureIsHandledOnceWithoutRequeueingModelCall() {
        var sender=mock(TelegramMessageSender.class);var feedback=new TelegramResourceFeedback(sender);
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        Object result=feedback.run(123,()->{attempts.incrementAndGet();throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);});
        assertThat(result).isNull();assertThat(attempts.get()).isEqualTo(1);
        verify(sender).sendMessage(123,"Лимит обращений пока исчерпан, попробуй позже");
    }
    @Test void unrelatedFailureRemainsRetryableByInbox() {
        var sender=mock(TelegramMessageSender.class);var feedback=new TelegramResourceFeedback(sender);
        assertThatThrownBy(()->feedback.run(123,()->{throw new IllegalStateException("network");})).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(sender);
    }
}
