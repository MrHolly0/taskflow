package ru.taskflow.shared.resources;

import org.springframework.http.HttpStatus;
import java.util.UUID;

public record ModelRequestContext(UUID userId, long deadlineMillis, int audioSeconds) {
    private static final ThreadLocal<ModelRequestContext> CURRENT = new ThreadLocal<>();
    public static ModelRequestContext current() { return CURRENT.get(); }
    public static ModelRequestContext required() {
        ModelRequestContext context = current();
        if (context == null) throw ResourceRejectedException.unavailable();
        context.remainingMillis();
        return context;
    }
    public long remainingMillis() {
        long remaining = deadlineMillis - System.currentTimeMillis();
        if (remaining <= 0) throw new ResourceRejectedException(HttpStatus.GATEWAY_TIMEOUT, "Время обработки истекло");
        return remaining;
    }
    public static Scope open(ModelRequestContext context) {
        ModelRequestContext previous = CURRENT.get();
        CURRENT.set(context);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
