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
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
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
                WHERE p.batch_id IS NULL AND p.sent_at IS NULL AND p.cancelled_at IS NULL
                  AND (p.expires_at <= ? OR NOT EXISTS (
                    SELECT 1 FROM scheduled_notifications n WHERE n.engagement_prompt_id = p.id))
                """, now, now);

        String offsets = timezoneOffsets(now);
        if ("{}".equals(offsets)) return;
        jdbc.update("""
                UPDATE engagement_prompts child SET cancelled_at=parent.cancelled_at
                FROM engagement_prompts parent WHERE child.batch_id=parent.id
                  AND parent.cancelled_at IS NOT NULL AND child.sent_at IS NULL AND child.cancelled_at IS NULL
                """);
        String workDue = ru.taskflow.shared.engagement.WorkCheckinSchedule.dueSql("candidate");
        String due = "CASE WHEN candidate.status='IN_PROGRESS' AND candidate.started_at IS NOT NULL THEN "
                + workDue + " ELSE GREATEST(candidate.deadline + INTERVAL '30 minutes', candidate.created_at + INTERVAL '30 minutes') END";
        var candidates = jdbc.query("SELECT u.id AS user_id,i.external_id,u.timezone,t.* " + USERS + """
                JOIN LATERAL (SELECT candidate.id AS task_id,candidate.title,candidate.deadline,CASE WHEN candidate.status='IN_PROGRESS' THEN candidate.started_at END AS started_at,candidate.estimate_minutes,
                """ + due + " AS due_at FROM tasks candidate WHERE candidate.user_id=u.id AND NOT candidate.is_deleted "
                + "AND candidate.status IN ('TODO','IN_PROGRESS') "
                + "AND ((candidate.status='IN_PROGRESS' AND candidate.started_at IS NOT NULL) OR candidate.deadline IS NOT NULL) "
                + "AND " + due + "<=? " + """
                  AND (candidate.planned_date IS NULL OR candidate.planned_date<=?
                    OR (candidate.status='IN_PROGRESS' AND candidate.started_at IS NOT NULL
                      AND (candidate.planned_date_set_at IS NULL OR candidate.planned_date_set_at<=candidate.started_at)))
                  AND NOT EXISTS (SELECT 1 FROM engagement_prompts p WHERE p.task_id=candidate.id AND p.cancelled_at IS NULL
                    AND ((candidate.status='IN_PROGRESS' AND candidate.started_at IS NOT NULL
                        AND p.kind='WORK_CHECKIN' AND p.started_snapshot=candidate.started_at)
                      OR ((candidate.status='TODO' OR candidate.started_at IS NULL) AND p.kind='TASK_CHECKIN'
                        AND p.deadline_snapshot=candidate.deadline)))
                  AND NOT EXISTS (SELECT 1 FROM reminders r WHERE r.task_id=candidate.id AND r.status='PENDING'
                    AND r.fire_at BETWEEN ? - INTERVAL '30 minutes' AND ? + INTERVAL '30 minutes')
                  ORDER BY due_at,candidate.id LIMIT 50) t ON true
                """ + AUDIENCE + " AND s.task_checkins_enabled ORDER BY t.due_at,u.id LIMIT 5000",
                (rs,row)->new Candidate(rs.getObject("user_id",UUID.class),rs.getString("external_id"),rs.getString("timezone"),
                        rs.getObject("task_id",UUID.class),rs.getString("title"),rs.getObject("deadline",OffsetDateTime.class),
                        rs.getObject("started_at",OffsetDateTime.class),rs.getObject("estimate_minutes",Integer.class),rs.getObject("due_at",OffsetDateTime.class)),
                offsets,now,now,now,now,now,now,now);
        Map<UUID,List<Candidate>> grouped = new LinkedHashMap<>();
        for (var candidate : candidates) grouped.computeIfAbsent(candidate.userId(), ignored -> new ArrayList<>()).add(candidate);
        for (var items : grouped.values()) {
            if (items.size()==1) enqueue(items.getFirst(),items.getFirst().kind(),now);
            else enqueueBatch(items,now);
        }

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
                INSERT INTO engagement_prompts(id, user_id, task_id, kind, deadline_snapshot, created_at, expires_at,started_snapshot,estimate_snapshot,due_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (
                  SELECT 1 FROM engagement_prompts p WHERE p.user_id = ? AND p.cancelled_at IS NULL
                    AND COALESCE(p.sent_at, p.created_at) > ? - INTERVAL '24 hours')
                ON CONFLICT DO NOTHING
                """, token, candidate.userId(), candidate.taskId(), kind, candidate.deadline(), now, now.plusDays(7),candidate.started(),candidate.estimate(),candidate.due(), candidate.userId(), now);
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

    private void enqueueBatch(List<Candidate> items, OffsetDateTime now) {
        var first=items.getFirst();UUID batch=UUID.randomUUID();
        int inserted=jdbc.update("""
                INSERT INTO engagement_prompts(id,user_id,kind,created_at,expires_at)
                SELECT ?,?,'CHECKIN_BATCH',?,? WHERE NOT EXISTS (SELECT 1 FROM engagement_prompts
                  WHERE user_id=? AND cancelled_at IS NULL AND COALESCE(sent_at,created_at)>?-INTERVAL '24 hours')
                """,batch,first.userId(),now,now.plusDays(7),first.userId(),now);
        if(inserted==0) return;
        for(int position=0;position<items.size();position++) {
            var item=items.get(position);
            jdbc.update("""
                    INSERT INTO engagement_prompts(id,user_id,task_id,kind,deadline_snapshot,started_snapshot,estimate_snapshot,due_at,
                      created_at,expires_at,batch_id,batch_position) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """,UUID.randomUUID(),item.userId(),item.taskId(),item.kind(),item.deadline(),item.started(),item.estimate(),item.due(),now,now.plusDays(7),batch,position);
        }
        try {
            var payload=mapper.writeValueAsString(Map.of("promptId",batch.toString(),"timezone",first.timezone(),"appUrl",appUrl));
            jdbc.update("""
                    INSERT INTO scheduled_notifications(id,user_id,channel,destination,fire_at,payload_type,payload,sent,retry_count,engagement_prompt_id)
                    VALUES (?,?,'TELEGRAM',?,?,'CHECKIN_BATCH',?::jsonb,false,0,?)
                    """,UUID.randomUUID(),first.userId(),first.destination(),now,payload,batch);
        } catch(JsonProcessingException e) { throw new IllegalStateException("Cannot serialize notification",e); }
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
                rs.getString("answer"), rs.getObject("cancelled_at", OffsetDateTime.class),rs.getObject("started_snapshot",OffsetDateTime.class)), promptId, userId);
        if (prompts.isEmpty()) return new Reply("Сообщение недоступно", false);
        var prompt = prompts.getFirst();
        if (prompt.answer() != null) return new Reply(answerText(prompt.answer()), true,prompt.answer());
        if (prompt.sentAt() == null) return new Reply("Сообщение ещё недоступно", false);
        var now = OffsetDateTime.now(clock);
        if ("RETURN_NUDGE".equals(prompt.kind()) && "mute".equals(action)) {
            users.updateSettings(userId, new UpdateSettingsRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, false));
        } else {
            if (prompt.cancelledAt() != null || !prompt.expiresAt().isAfter(now)
                    || !java.util.Set.of("TASK_CHECKIN","WORK_CHECKIN").contains(prompt.kind()) || "mute".equals(action))
                return new Reply("Сообщение уже неактуально, открой текущие дела в Мунине", true,"stale");
            boolean current = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM tasks WHERE id = ? AND user_id = ? AND NOT is_deleted
                      AND status IN ('TODO', 'IN_PROGRESS') AND deadline IS NOT DISTINCT FROM ?
                      AND (planned_date IS NULL OR planned_date <= ? OR (?='WORK_CHECKIN'
                        AND (planned_date_set_at IS NULL OR planned_date_set_at<=started_at)))
                      AND (? <> 'WORK_CHECKIN' OR (status='IN_PROGRESS' AND started_at=?)))
                    """, Boolean.class, taskId, userId, prompt.deadline(), now,prompt.kind(),prompt.kind(),prompt.started()));
            if (!current) return new Reply("Задача уже изменена или завершена", true,"stale");
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
        return new Reply(answerText(action), true,action);
    }

    private String answerText(String action) {
        return switch (action) {
            case "done" -> "✅ Задача завершена";
            case "working" -> "⏳ Задача в работе";
            case "later" -> "🔔 Напомню через час\nСрок задачи не изменён";
            case "mute" -> "Приглашения вернуться выключены\nНапоминания о задачах не менялись";
            default -> "Ответ уже сохранён";
        };
    }

    private record Candidate(UUID userId, String destination, String timezone, UUID taskId, String title,
                             OffsetDateTime deadline,OffsetDateTime started,Integer estimate,OffsetDateTime due) {
        String kind() { return started==null?"TASK_CHECKIN":"WORK_CHECKIN"; }
        Candidate(UUID userId,String destination,String timezone,UUID taskId,String title,OffsetDateTime deadline) {
            this(userId,destination,timezone,taskId,title,deadline,null,null,null);
        }
    }
    private record Prompt(String kind, OffsetDateTime deadline, OffsetDateTime expiresAt, OffsetDateTime sentAt,
                          String answer, OffsetDateTime cancelledAt,OffsetDateTime started) {}
}
