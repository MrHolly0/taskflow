package ru.taskflow.task.application.read;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import ru.taskflow.task.api.dto.*;
import ru.taskflow.task.infrastructure.persistence.*;
import ru.taskflow.user.api.UserService;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskReadService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TaskRepository tasks;
    private final ReminderRepository reminders;
    private final RecurrenceRepository recurrences;
    private final TaskMapper mapper;
    private final UserService users;

    public record Filter(String status, String group, String query, String context, String order,
                         OffsetDateTime completedSince, OffsetDateTime completedUntil, List<UUID> revealIds) {}
    public record TaskPage(List<TaskResponse> content, long totalElements, int number, int size) {}
    public record GroupCount(String groupId, long active, long history, long weeklyDone) {}
    public record Overview(long active, long history, Map<String, Long> statuses, List<GroupCount> groups) {}

    public TaskPage page(UUID userId, Filter filter, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100 || filter.query().length() > 200
                || filter.revealIds().size() > 20) throw badFilter();
        MapSqlParameterSource params = new MapSqlParameterSource("user", userId);
        StringBuilder where = new StringBuilder("user_id = :user AND is_deleted = false");
        String status = filter.status();
        switch (status) {
            case "active" -> where.append(" AND status IN ('TODO','IN_PROGRESS')");
            case "history" -> where.append(" AND status IN ('DONE','CANCELLED')");
            case "all" -> { }
            case "TODO", "IN_PROGRESS", "DONE", "CANCELLED" -> {
                where.append(" AND status = :status"); params.addValue("status", status);
            }
            default -> throw badFilter();
        }
        if (!"all".equals(filter.group())) {
            if ("none".equals(filter.group())) where.append(" AND group_id IS NULL");
            else {
                try { params.addValue("group", UUID.fromString(filter.group())); }
                catch (IllegalArgumentException e) { throw badFilter(); }
                where.append(" AND group_id = :group");
            }
        }
        if (!filter.query().isBlank()) {
            where.append(" AND LOWER(title) LIKE LOWER(:query) ESCAPE '!'");
            params.addValue("query", "%" + filter.query().strip().replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_") + "%");
        }
        OffsetDateTime now = OffsetDateTime.now();
        switch (filter.context()) {
            case "all" -> { }
            case "now" -> {
                where.append(" AND status IN ('TODO','IN_PROGRESS') AND (priority = 'URGENT' OR status = 'IN_PROGRESS' OR deadline < :now)");
                params.addValue("now", now);
            }
            case "today", "week" -> {
                ZoneId zone = users.getTimezone(userId);
                int days = "week".equals(filter.context()) ? 7 : 1;
                params.addValue("end", LocalDate.now(zone).plusDays(days).atStartOfDay(zone).toOffsetDateTime());
                where.append(" AND status IN ('TODO','IN_PROGRESS') AND (COALESCE(planned_date,deadline) IS NULL OR COALESCE(planned_date,deadline) < :end)");
            }
            default -> throw badFilter();
        }
        if (filter.completedSince() != null) {
            where.append(" AND completed_at >= :since"); params.addValue("since", filter.completedSince());
        }
        if (filter.completedUntil() != null) {
            where.append(" AND completed_at < :until"); params.addValue("until", filter.completedUntil());
        }
        String order = switch (filter.order()) {
            case "default" -> "created_at DESC";
            case "deadline" -> "deadline ASC NULLS LAST";
            case "priority" -> "CASE priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END";
            case "status" -> "CASE status WHEN 'IN_PROGRESS' THEN 0 ELSE 1 END";
            case "completed" -> "completed_at DESC NULLS LAST";
            case "recommendation" -> {
                params.addValue("recommendationNow", now);
                params.addValue("recommendationDayEnd", LocalDate.now(users.getTimezone(userId)).plusDays(1)
                        .atStartOfDay(users.getTimezone(userId)).toOffsetDateTime());
                yield "CASE WHEN status='IN_PROGRESS' THEN 0 WHEN planned_date >= :recommendationDayEnd THEN 6 "
                        + "WHEN deadline < :recommendationNow THEN 1 WHEN priority='URGENT' THEN 2 "
                        + "WHEN planned_date IS NOT NULL THEN 3 WHEN deadline IS NOT NULL THEN 4 ELSE 5 END, "
                        + "deadline ASC NULLS LAST";
            }
            default -> throw badFilter();
        };
        if (!filter.revealIds().isEmpty()) {
            StringJoiner cases = new StringJoiner(" ", "CASE id ", " ELSE 999 END, ");
            for (int i = 0; i < filter.revealIds().size(); i++) {
                cases.add("WHEN :reveal" + i + " THEN " + i);
                params.addValue("reveal" + i, filter.revealIds().get(i));
            }
            order = cases + order;
        }
        long total = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE " + where, params, Long.class));
        params.addValue("size", size).addValue("offset", (long) page * size);
        List<UUID> ids = jdbc.query("SELECT id FROM tasks WHERE " + where + " ORDER BY " + order
                + ", created_at DESC, id LIMIT :size OFFSET :offset", params, (rs, n) -> rs.getObject("id", UUID.class));
        return new TaskPage(responses(ids), total, page, size);
    }

    public Overview overview(UUID userId) {
        var params = new MapSqlParameterSource("user", userId).addValue("since",
                LocalDate.now(users.getTimezone(userId)).minusDays(6).atStartOfDay(users.getTimezone(userId)).toOffsetDateTime());
        Map<String, Long> statuses = new LinkedHashMap<>();
        jdbc.query("SELECT status, COUNT(*) AS n FROM tasks WHERE user_id=:user AND is_deleted=false GROUP BY status",
                params, rs -> { statuses.put(rs.getString("status"), rs.getLong("n")); });
        List<GroupCount> groups = jdbc.query("""
                SELECT group_id,
                  COUNT(*) FILTER (WHERE status IN ('TODO','IN_PROGRESS')) AS active,
                  COUNT(*) FILTER (WHERE status IN ('DONE','CANCELLED')) AS history,
                  COUNT(*) FILTER (WHERE status='DONE' AND completed_at >= :since) AS weekly
                FROM tasks WHERE user_id=:user AND is_deleted=false GROUP BY group_id
                """, params, (rs, n) -> new GroupCount(Objects.toString(rs.getObject("group_id"), ""),
                rs.getLong("active"), rs.getLong("history"), rs.getLong("weekly")));
        return new Overview(statuses.getOrDefault("TODO", 0L) + statuses.getOrDefault("IN_PROGRESS", 0L),
                statuses.getOrDefault("DONE", 0L) + statuses.getOrDefault("CANCELLED", 0L), statuses, groups);
    }

    private List<TaskResponse> responses(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        var byId = tasks.findAllByIdInWithCollections(ids).stream().collect(Collectors.toMap(TaskJpaEntity::getId, Function.identity()));
        var byTask = reminders.findByTaskIdInAndStatusOrderByFireAtAsc(ids, ReminderStatus.PENDING).stream()
                .collect(Collectors.groupingBy(r -> r.getTask().getId(), Collectors.mapping(mapper::toReminderResponse, Collectors.toList())));
        var rules = recurrences.findAllById(ids).stream().collect(Collectors.toMap(RecurrenceJpaEntity::getTaskId, this::rule));
        return ids.stream().filter(byId::containsKey).map(id -> {
            TaskResponse t = mapper.toResponse(byId.get(id));
            return new TaskResponse(t.id(), t.title(), t.description(), t.priority(), t.status(), t.deadline(),
                    t.plannedDate(), t.estimateMinutes(), t.source(), t.groupId(), t.groupName(), t.tags(),
                    t.createdAt(), t.updatedAt(), t.completedAt(), byTask.getOrDefault(id, List.of()),
                    t.startedAt(), t.plannedDateSetAt(), rules.get(id), t.persistentReminder());
        }).toList();
    }

    private RecurrenceRule rule(RecurrenceJpaEntity r) {
        List<DayOfWeek> days = r.getDaysOfWeek() == null || r.getDaysOfWeek().isBlank() ? List.of()
                : Arrays.stream(r.getDaysOfWeek().split(",")).map(value -> DayOfWeek.of(Integer.parseInt(value.strip()))).toList();
        return new RecurrenceRule(r.getType(), r.getIntervalN(), days, r.getDayOfMonth(), r.getEndsAt());
    }

    private ResponseStatusException badFilter() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректные параметры списка задач");
    }
}
