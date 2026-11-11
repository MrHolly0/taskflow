package ru.taskflow.telegram.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUpdate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class TelegramUpdateInbox {

    static final int MAX_ATTEMPTS = 6;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final int maxPending;

    public TelegramUpdateInbox(JdbcTemplate jdbc, ObjectMapper objectMapper,
            @Value("${app.telegram.inbox.max-pending:1000}") int maxPending) {
        if (maxPending < 1) throw new IllegalArgumentException("max-pending must be positive");
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.maxPending = maxPending;
    }

    @Transactional
    public void enqueue(TelegramUpdate update) {
        // Сериализуем только короткую приёмку, не обработку и внешние вызовы.
        jdbc.execute("SELECT pg_advisory_xact_lock(746701)");
        Integer duplicate = jdbc.queryForObject(
                "SELECT COUNT(*) FROM telegram_update_inbox WHERE update_id = ?", Integer.class, update.updateId());
        if (duplicate != null && duplicate > 0) return;
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM telegram_update_inbox WHERE status IN ('PENDING', 'PROCESSING')", Integer.class);
        if (pending != null && pending >= maxPending) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "очередь сообщений заполнена");
        }
        jdbc.update("""
                INSERT INTO telegram_update_inbox (update_id, sender_id, payload, widget_query)
                VALUES (?, ?, ?::jsonb, ?) ON CONFLICT (update_id) DO NOTHING
                """, update.updateId(), senderId(update), json(update), update.widgetQuery()!=null);
    }

    public Optional<ClaimedUpdate> claim() {
        jdbc.update("""
                WITH exhausted AS (
                    SELECT update_id FROM telegram_update_inbox
                    WHERE status IN ('PENDING', 'PROCESSING') AND attempts >= ?
                        AND (lease_until IS NULL OR lease_until <= NOW())
                    ORDER BY update_id LIMIT 100 FOR UPDATE SKIP LOCKED
                )
                UPDATE telegram_update_inbox i SET status = 'FAILED', last_error = 'ATTEMPTS_EXHAUSTED',
                    lease_until = NULL, lease_token = NULL
                FROM exhausted e WHERE i.update_id = e.update_id
                """, MAX_ATTEMPTS);
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                WITH candidate AS (
                    SELECT i.update_id FROM telegram_update_inbox i
                    WHERE NOT i.widget_query AND i.attempts < ? AND ((i.status = 'PENDING' AND i.next_attempt_at <= NOW())
                        OR (i.status = 'PROCESSING' AND i.lease_until < NOW()))
                    AND NOT EXISTS (
                        SELECT 1 FROM telegram_update_inbox earlier
                        WHERE NOT earlier.widget_query AND earlier.sender_id = i.sender_id AND earlier.update_id < i.update_id
                            AND earlier.status IN ('PENDING', 'PROCESSING')
                    )
                    ORDER BY i.update_id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE telegram_update_inbox i
                SET status = 'PROCESSING', lease_token = ?, lease_until = NOW() + INTERVAL '5 minutes',
                    attempts = attempts + 1
                FROM candidate c WHERE i.update_id = c.update_id
                RETURNING i.update_id, i.payload::text, i.attempts
                """, (rs, row) -> new ClaimedUpdate(rs.getLong("update_id"),
                read(rs.getString("payload"), TelegramUpdate.class), token, rs.getInt("attempts")), MAX_ATTEMPTS, token)
                .stream().findFirst();
    }

    public Optional<ClaimedUpdate> claimWidget() {
        jdbc.update("""
                UPDATE telegram_update_inbox SET status='FAILED',last_error='WIDGET_QUERY_EXPIRED',
                  lease_token=NULL,lease_until=NULL
                WHERE widget_query AND status IN ('PENDING','PROCESSING') AND received_at < NOW()-INTERVAL '12 seconds'
                  AND (lease_until IS NULL OR lease_until<=NOW())
                """);
        UUID token=UUID.randomUUID();
        return jdbc.query("""
                WITH candidate AS (
                  SELECT i.update_id FROM telegram_update_inbox i
                  WHERE i.widget_query AND i.status='PENDING' AND i.next_attempt_at<=NOW()
                    AND i.received_at>NOW()-INTERVAL '12 seconds'
                    AND NOT EXISTS (SELECT 1 FROM telegram_update_inbox e WHERE e.widget_query
                      AND e.sender_id=i.sender_id AND e.update_id<i.update_id AND e.status IN ('PENDING','PROCESSING'))
                  ORDER BY i.update_id LIMIT 1 FOR UPDATE SKIP LOCKED)
                UPDATE telegram_update_inbox i SET status='PROCESSING',lease_token=?,
                  lease_until=NOW()+INTERVAL '30 seconds',attempts=attempts+1
                FROM candidate c WHERE c.update_id=i.update_id
                RETURNING i.update_id,i.payload::text,i.attempts
                """,(rs,row)->new ClaimedUpdate(rs.getLong("update_id"),read(rs.getString("payload"),TelegramUpdate.class),
                token,rs.getInt("attempts")),token).stream().findFirst();
    }

    public java.time.Instant widgetDeadline(long updateId) {
        return jdbc.queryForObject("SELECT received_at+INTERVAL '10 seconds' FROM telegram_update_inbox WHERE update_id=? AND widget_query",
                (rs,row)->rs.getObject(1,java.time.OffsetDateTime.class).toInstant(),updateId);
    }

    public boolean renew(ClaimedUpdate update) {
        return jdbc.update("""
                UPDATE telegram_update_inbox SET lease_until = NOW() + INTERVAL '5 minutes'
                WHERE update_id = ? AND lease_token = ? AND status = 'PROCESSING'
                """, update.id(), update.token()) == 1;
    }

    public void complete(ClaimedUpdate update) {
        jdbc.update("""
                UPDATE telegram_update_inbox SET status = 'DONE', processed_at = NOW(),
                    payload = '{}'::jsonb, checkpoints = '{}'::jsonb, lease_until = NULL, lease_token = NULL,
                    last_error = NULL
                WHERE update_id = ? AND lease_token = ? AND status = 'PROCESSING'
                """, update.id(), update.token());
    }

    public void fail(ClaimedUpdate update, boolean retryable, Duration delay, String errorType) {
        jdbc.update("""
                UPDATE telegram_update_inbox SET status = ?, next_attempt_at = NOW() + (? * INTERVAL '1 millisecond'),
                    lease_until = NULL, lease_token = NULL, last_error = ?
                WHERE update_id = ? AND lease_token = ? AND status = 'PROCESSING'
                """, retryable ? "PENDING" : "FAILED", delay.toMillis(), errorType, update.id(), update.token());
    }

    public <T> Optional<T> checkpoint(long updateId, String name, Class<T> type) {
        return jdbc.query("SELECT (checkpoints -> ?)::text FROM telegram_update_inbox WHERE update_id = ?",
                        (rs, row) -> Optional.ofNullable(rs.getString(1)).map(value -> read(value, type)), name, updateId)
                .stream().findFirst().orElse(Optional.empty());
    }

    public void checkpoint(long updateId, String name, Object value) {
        jdbc.update("""
                UPDATE telegram_update_inbox SET checkpoints = jsonb_set(checkpoints, ARRAY[?]::text[], ?::jsonb)
                WHERE update_id = ?
                """, name, json(value), updateId);
    }

    public void prune() {
        jdbc.update("""
                DELETE FROM telegram_update_inbox WHERE update_id IN (
                    SELECT update_id FROM telegram_update_inbox
                    WHERE (status = 'DONE' AND processed_at < NOW() - INTERVAL '7 days')
                        OR (status = 'FAILED' AND received_at < NOW() - INTERVAL '30 days')
                    ORDER BY update_id LIMIT 1000
                )
                """);
    }

    private long senderId(TelegramUpdate update) {
        if (update.widgetQuery()!=null && update.widgetQuery().from()!=null) return update.widgetQuery().from().id();
        if (update.callbackQuery() != null && update.callbackQuery().from() != null) {
            return update.callbackQuery().from().id();
        }
        if (update.message() != null && update.message().from() != null) return update.message().from().id();
        return 0;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Не удалось сохранить сообщение", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось прочитать сообщение", e);
        }
    }

    public record ClaimedUpdate(long id, TelegramUpdate update, UUID token, int attempt) {}
}
