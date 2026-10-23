package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class TelegramUpdateCheckpoint {

    private final TelegramUpdateInbox inbox;
    private final ThreadLocal<Long> activeUpdate = new ThreadLocal<>();

    public void within(long updateId, Runnable action) {
        activeUpdate.set(updateId);
        try {
            action.run();
        } finally {
            activeUpdate.remove();
        }
    }

    public String requestKey() {
        Long updateId = activeUpdate.get();
        return updateId == null ? null : "tg:update:" + updateId;
    }

    public <T> T once(String name, Class<T> type, Supplier<T> action) {
        Long updateId = activeUpdate.get();
        if (updateId == null) return action.get();
        return inbox.checkpoint(updateId, name, type).orElseGet(() -> {
            T result = action.get();
            inbox.checkpoint(updateId, name, result);
            return result;
        });
    }
}
