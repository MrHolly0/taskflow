package ru.taskflow.telegram;

import org.junit.jupiter.api.Test;
import ru.taskflow.telegram.application.TelegramUpdateCheckpoint;
import ru.taskflow.telegram.application.TelegramUpdateInbox;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramUpdateCheckpointTest {

    @Test
    void retryReusesSavedResultAndClearsThreadContextAfterFailure() {
        TelegramUpdateInbox inbox = mock(TelegramUpdateInbox.class);
        var checkpoint = new TelegramUpdateCheckpoint(inbox);
        AtomicInteger generations = new AtomicInteger();
        when(inbox.checkpoint(42L, "reply", String.class)).thenReturn(Optional.empty(), Optional.of("saved"));
        assertThatThrownBy(() -> checkpoint.within(42, () -> {
            assertThat(checkpoint.requestKey()).isEqualTo("tg:update:42");
            checkpoint.once("reply", String.class, () -> {
                generations.incrementAndGet();
                return "saved";
            });
            throw new IllegalStateException("send failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(checkpoint.requestKey()).isNull();
        checkpoint.within(42, () -> assertThat(checkpoint.once("reply", String.class, () -> {
            generations.incrementAndGet();
            return "duplicate";
        })).isEqualTo("saved"));
        assertThat(generations.get()).isEqualTo(1);
    }
}
