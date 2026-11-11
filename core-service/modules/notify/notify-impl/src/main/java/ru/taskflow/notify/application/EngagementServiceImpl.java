package ru.taskflow.notify.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.notify.api.EngagementService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;

import java.time.Clock;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EngagementServiceImpl implements EngagementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TaskService tasks;
    private final UserService users;
    private final Clock clock;

    @Value("${app.telegram.miniapp-url:}")
    private String appUrl;

    private static final String USERS = """
            FROM users u JOIN user_settings s ON s.user_id = u.id
            JOIN jsonb_each_text(CAST(? AS jsonb)) zone ON zone.key = u.timezone
            JOIN LATERAL (SELECT identity.external_id FROM user_identities identity
              WHERE identity.user_id = u.id AND identity.provider = 'TELEGRAM'
              ORDER BY identity.created_at DESC, identity.id LIMIT 1) i ON true
            """;
    private static final String AUDIENCE = """
            WHERE s.notifications_enabled AND s.notify_telegram
              AND ((? AT TIME ZONE 'UTC') + zone.value::int * INTERVAL '1 second')::time >= TIME '09:00'
              AND ((? AT TIME ZONE 'UTC') + zone.value::int * INTERVAL '1 second')::time < TIME '21:00'
              AND NOT EXISTS (SELECT 1 FROM engagement_prompts p WHERE p.user_id = u.id
                AND p.cancelled_at IS NULL AND (COALESCE(p.sent_at, p.created_at) > ? - INTERVAL '24 hours'
                  OR (p.sent_at IS NULL AND EXISTS (SELECT 1 FROM scheduled_notifications n
                    WHERE n.engagement_prompt_id = p.id AND NOT n.sent AND n.failed_at IS NULL))))
            """;

    @Override
    @Transactional(timeout = 60)
    public void plan() {
        // Тот же transaction-scoped lock защищает и ручной запуск планирования.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(910005)", Boolean.class))) return;
        var now = OffsetDateTime.now(clock);
        jdbc.update("""
                UPDATE engagement_prompts p SET cancelled_at = ?
                WHERE p.sent_at IS NULL AND p.cancelled_at IS NULL
                  AND (p.expires_at <= ? OR NOT EXISTS (
                    SELECT 1 FROM scheduled_notifications n WHERE n.engagement_prompt_id = p.id))
                """, now, now);

        String offsets = timezoneOffsets(now);
        if ("{}".equals(offsets)) return;
        var checkins = jdbc.query("""
                SELECT u.id AS user_id, i.external_id, u.timezone, t.id AS task_id, t.title, t.deadline
                """ + USERS + """
                JOIN LATERAL (SELECT candidate.id, candidate.title, candidate.deadline FROM tasks candidate
                  WHERE candidate.user_id = u.id AND NOT candidate.is_deleted
                    AND candidate.status IN ('TODO', 'IN_PROGRESS')
                    AND candidate.deadline >= s.task_checkins_enabled_at
                    AND candidate.deadline >= ? - INTERVAL '24 hours'
                    AND candidate.deadline <= ? - INTERVAL '30 minutes'
                    AND candidate.created_at <= candidate.deadline
                    AND (candidate.planned_date IS NULL OR candidate.planned_date <= ?)
                    AND EXISTS (SELECT 1 FROM reminders r WHERE r.task_id = candidate.id AND r.status = 'PENDING')
                    AND NOT EXISTS (SELECT 1 FROM reminders r WHERE r.task_id = candidate.id AND r.status = 'PENDING'
                      AND r.fire_at BETWEEN ? - INTERVAL '30 minutes' AND ? + INTERVAL '30 minutes')
                    AND NOT EXISTS (SELECT 1 FROM engagement_prompts p WHERE p.task_id = candidate.id
                      AND p.deadline_snapshot = candidate.deadline AND p.kind = 'TASK_CHECKIN')
                  ORDER BY candidate.deadline, candidate.id LIMIT 1) t ON true
                """ + AUDIENCE + """
                  AND s.task_checkins_enabled
                  ORDER BY t.deadline, u.id LIMIT 100
                """,
                (rs, row) -> new Candidate(rs.getObject("user_id", UUID.class), rs.getString("external_id"),
                        rs.getString("timezone"), rs.getObject("task_id", UUID.class), rs.getString("title"),
                        rs.getObject("deadline", OffsetDateTime.class)), offsets, now, now, now, now, now, now, now, now);
        for (var candidate : checkins) enqueue(candidate, "TASK_CHECKIN", now);

        var returns = jdbc.query("SELECT u.id AS user_id, i.external_id, u.timezone " + USERS + AUDIENCE + """
                  AND s.return_nudges_enabled
                  AND GREATEST(u.last_active_at, s.return_nudges_enabled_at) <= ? - INTERVAL '3 days'
                  AND NOT EXISTS (SELECT 1 FROM engagement_prompts p WHERE p.user_id = u.id
                    AND p.kind = 'RETURN_NUDGE' AND p.cancelled_at IS NULL
                    AND COALESCE(p.sent_at, p.created_at) > ? - INTERVAL '7 days')
                  ORDER BY u.last_active_at, u.id LIMIT 100
                """, (rs, row) -> new Candidate(rs.getObject("user_id", UUID.class), rs.getString("external_id"),
                rs.getString("timezone"), null, null, null), offsets, now, now, now, now, now);
        for (var candidate : returns) enqueue(candidate, "RETURN_NUDGE", now);
    }

    private String timezoneOffsets(OffsetDateTime now) {
        Map<String, Integer> offsets = new HashMap<>();
        jdbc.queryForList("""
                SELECT DISTINCT u.timezone FROM users u JOIN user_settings s ON s.user_id = u.id
                WHERE s.notifications_enabled AND s.notify_telegram
                  AND (s.task_checkins_enabled OR s.return_nudges_enabled)
                """, String.class).forEach(timezone -> {
            try {
                offsets.put(timezone, ZoneId.of(timezone).getRules().getOffset(now.toInstant()).getTotalSeconds());
            } catch (DateTimeException invalidLegacyTimezone) {
                // Повреждённая старая настройка не останавливает обработку остальных учёток.
            }
        });
        try {
            return mapper.writeValueAsString(offsets);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize timezones", failure);
        }
    }

    private void enqueue(Candidate candidate, String kind, OffsetDateTime now) {
        var token = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO engagement_prompts(id, user_id, task_id, kind, deadline_snapshot, created_at, expires_at)
                SELECT ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (
                  SELECT 1 FROM engagement_prompts p WHERE p.user_id = ? AND p.cancelled_at IS NULL
                    AND COALESCE(p.sent_at, p.created_at) > ? - INTERVAL '24 hours')
                ON CONFLICT DO NOTHING
                """, token, candidate.userId(), candidate.taskId(), kind, candidate.deadline(), now, now.plusDays(7), candidate.userId(), now);
        if (inserted == 0) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("promptId", token.toString());
        payload.put("taskId", candidate.taskId());
        payload.put("taskTitle", candidate.title());
        payload.put("deadline", candidate.deadline() == null ? null : candidate.deadline().toString());
        payload.put("timezone", candidate.timezone());
        payload.put("appUrl", appUrl);
        try {
            jdbc.update("""
                    INSERT INTO scheduled_notifications(id, user_id, task_id, channel, destination, fire_at,
                      payload_type, payload, sent, retry_count, engagement_prompt_id)
                    VALUES (?, ?, ?, 'TELEGRAM', ?, ?, ?, CAST(? AS jsonb), false, 0, ?)
                    """, UUID.randomUUID(), candidate.userId(), candidate.taskId(), candidate.destination(), now,
                    kind, mapper.writeValueAsString(payload), token);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize notification", failure);
        }
    }

    @Override
    @Transactional
    public Reply reply(UUID userId, UUID promptId, String action) {
        if (!java.util.Set.of("done", "working", "later", "mute").contains(action))
            return new Reply("Неизвестная команда", false);
        // Порядок блокировок совпадает с изменениями задачи: задача перед уведомлением.
        var taskIds = jdbc.query("SELECT task_id FROM engagement_prompts WHERE id = ? AND user_id = ?",
                (rs, row) -> rs.getObject("task_id", UUID.class), promptId, userId);
        if (taskIds.isEmpty()) return new Reply("Сообщение недоступно", false);
        UUID taskId = taskIds.getFirst();
        if (taskId != null) jdbc.query("SELECT id FROM tasks WHERE id = ? AND user_id = ? FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), taskId, userId);
        var prompts = jdbc.query("""
                SELECT * FROM engagement_prompts WHERE id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> new Prompt(rs.getString("kind"), rs.getObject("deadline_snapshot", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("sent_at", OffsetDateTime.class),
                rs.getString("answer"), rs.getObject("cancelled_at", OffsetDateTime.class)), promptId, userId);
        if (prompts.isEmpty()) return new Reply("Сообщение недоступно", false);
        var prompt = prompts.getFirst();
        if (prompt.answer() != null) return new Reply(answerText(prompt.answer()), true);
        if (prompt.sentAt() == null) return new Reply("Сообщение ещё недоступно", false);
        var now = OffsetDateTime.now(clock);
        if ("RETURN_NUDGE".equals(prompt.kind()) && "mute".equals(action)) {
            users.updateSettings(userId, new UpdateSettingsRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, false));
        } else {
            if (prompt.cancelledAt() != null || !prompt.expiresAt().isAfter(now)
                    || !"TASK_CHECKIN".equals(prompt.kind()) || "mute".equals(action))
                return new Reply("Это сообщение уже неактуально. Проверь текущие дела в Мунине.", true);
            boolean current = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM tasks WHERE id = ? AND user_id = ? AND NOT is_deleted
                      AND status IN ('TODO', 'IN_PROGRESS') AND deadline = ?
                      AND (planned_date IS NULL OR planned_date <= ?))
                    """, Boolean.class, taskId, userId, prompt.deadline(), now));
            if (!current) return new Reply("Задача уже изменена или завершена. Оставил её как есть.", true);
            switch (action) {
                case "done" -> tasks.complete(userId, taskId);
                case "working" -> tasks.update(userId, taskId,
                        new UpdateTaskRequest(null, null, null, TaskStatus.IN_PROGRESS, null, null, null, null, null));
                case "later" -> {
                    var settings = users.getSettings(userId);
                    if (!settings.notificationsEnabled() || !settings.notifyTelegram())
                        return new Reply("Напоминания в Telegram выключены. Включи их в настройках Мунина.", false);
                    tasks.scheduleReminder(userId, taskId, now.plusHours(1));
                }
                default -> throw new IllegalStateException("Unexpected action");
            }
        }
        jdbc.update("UPDATE engagement_prompts SET answered_at = ?, answer = ? WHERE id = ?", now, action, promptId);
        return new Reply(answerText(action), true);
    }

    private String answerText(String action) {
        return switch (action) {
            case "done" -> "✅ Задача завершена.";
            case "working" -> "⏳ Задача в работе. Больше не спрашиваю об этом сроке.";
            case "later" -> "🔔 Напомню через час. Срок задачи не изменён.";
            case "mute" -> "Приглашения вернуться выключены. Напоминания о задачах остались как были.";
            default -> "Ответ уже сохранён.";
        };
    }

    private record Candidate(UUID userId, String destination, String timezone, UUID taskId, String title,
                             OffsetDateTime deadline) {}
    private record Prompt(String kind, OffsetDateTime deadline, OffsetDateTime expiresAt, OffsetDateTime sentAt,
                          String answer, OffsetDateTime cancelledAt) {}
}
