package ru.taskflow.task.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.task.api.*;
import ru.taskflow.task.api.dto.*;
import ru.taskflow.task.infrastructure.persistence.TaskRepository;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.audit.api.AuditEventType;
import ru.taskflow.shared.exception.ValidationException;
import ru.taskflow.shared.exception.NotFoundException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TaskOperationServiceImpl implements TaskOperationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TaskRepository repository;
    private final TaskService tasks;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;
    public TaskOperationServiceImpl(JdbcTemplate jdbc,ObjectMapper mapper,TaskRepository repository,TaskService tasks,
            NotificationService notifications,AuditService audit,Clock clock) {
        this.jdbc=jdbc;this.mapper=mapper;this.repository=repository;this.tasks=tasks;
        this.notifications=notifications;this.audit=audit;this.clock=clock;
    }
    @Override @Transactional
    public TaskResponse create(UUID user,CreateTaskRequest request,UUID operation) {
        var existing=find(user,operation);
        if(existing.isPresent()) {
            if(existing.get().undone()) throw new ValidationException("Действие уже отменено");
            return tasks.findById(user,existing.get().taskId());
        }
        var task=tasks.createQuick(user,request);
        repository.flush();
        remember(user,operation,task.id(),"CREATE",null);
        return task;
    }
    @Override @Transactional
    public void complete(UUID user,UUID taskId,UUID operation) {
        var task=repository.findForUpdate(taskId,user).orElseThrow(()->new NotFoundException("Задача недоступна"));
        if(find(user,operation).isPresent()) return;
        if(task.getStatus()!=TaskStatus.TODO && task.getStatus()!=TaskStatus.IN_PROGRESS)
            throw new ValidationException("Задача уже закрыта");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM recurrences WHERE task_id=?)",Boolean.class,taskId)))
            throw new ValidationException("Завершение повторяющейся задачи нужно подтвердить");
        String before=snapshot(user,taskId);
        tasks.complete(user,taskId);repository.flush();
        remember(user,operation,taskId,"COMPLETE",before);
    }
    @Override @Transactional(readOnly=true)
    public Optional<TaskOperationReceipt> find(UUID user,UUID operation) {
        return jdbc.query("""
                SELECT o.* FROM task_operations o JOIN tasks t ON t.id=o.task_id AND t.user_id=o.user_id
                WHERE o.id=? AND o.user_id=?
                """,(rs,row)->new TaskOperationReceipt(operation,rs.getObject("task_id",UUID.class),rs.getString("kind"),
                rs.getObject("expires_at",OffsetDateTime.class),rs.getObject("undone_at")!=null),operation,user).stream().findFirst();
    }
    @Override @Transactional
    public TaskUndoResult undo(UUID user,UUID operation) {
        var preview=find(user,operation).orElseThrow(()->new NotFoundException("Действие недоступно"));
        jdbc.query("SELECT id FROM tasks WHERE id=? AND user_id=? FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),preview.taskId(),user);
        var rows=jdbc.query("SELECT before_state::text,after_state::text,expires_at,undone_at FROM task_operations WHERE id=? AND user_id=? FOR UPDATE",
                (rs,row)->new Stored(rs.getString(1),rs.getString(2),rs.getObject(3,OffsetDateTime.class),rs.getObject(4)!=null),operation,user);
        if(rows.isEmpty()) throw new NotFoundException("Действие недоступно");
        var stored=rows.getFirst();
        if(stored.undone()) return new TaskUndoResult(preview.taskId(),preview.kind(),true);
        if(!stored.expires().isAfter(OffsetDateTime.now(clock))) throw new ValidationException("Время отмены истекло, задачу можно изменить вручную");
        boolean unchanged=Boolean.TRUE.equals(jdbc.queryForObject("SELECT ?::jsonb=?::jsonb",Boolean.class,stored.after(),snapshot(user,preview.taskId())));
        if(!unchanged) throw new ValidationException("Задача уже изменилась, открой её для исправления");
        if(preview.kind().equals("CREATE")) tasks.delete(user,preview.taskId());
        else restoreCompletion(user,preview.taskId(),stored.before());
        repository.flush();
        jdbc.update("UPDATE task_operations SET undone_at=? WHERE id=?",OffsetDateTime.now(clock),operation);
        return new TaskUndoResult(preview.taskId(),preview.kind(),true);
    }
    private void remember(UUID user,UUID operation,UUID task,String kind,String before) {
        jdbc.update("INSERT INTO task_operations(id,user_id,task_id,kind,before_state,after_state,expires_at) VALUES (?,?,?,?,?::jsonb,?::jsonb,?)",
                operation,user,task,kind,before,snapshot(user,task),OffsetDateTime.now(clock).plusHours(24));
    }
    private String snapshot(UUID user,UUID task) {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object('task',to_jsonb(t)-ARRAY['updated_at','last_shown_in_focus_at','first_step_hint',
                  'first_step_generated_at','planned_date_suggestion_attempted_at']::text[],
                  'reminders',COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id) FROM reminders r WHERE r.task_id=t.id),'[]'::jsonb),
                  'tags',COALESCE((SELECT jsonb_agg(tt.tag_id ORDER BY tt.tag_id) FROM task_tags tt WHERE tt.task_id=t.id),'[]'::jsonb),
                  'recurrence',(SELECT to_jsonb(recur) FROM recurrences recur WHERE recur.task_id=t.id))::text
                FROM tasks t WHERE t.id=? AND t.user_id=?
                """,String.class,task,user);
    }
    private void restoreCompletion(UUID user,UUID taskId,String before) {
        try {
            var state=mapper.readTree(before);var original=state.path("task");
            var task=repository.findForUpdate(taskId,user).orElseThrow(()->new NotFoundException("Задача недоступна"));
            task.setStatus(TaskStatus.valueOf(original.path("status").asText()));
            task.setCompletedAt(date(original.get("completed_at")));task.setStartedAt(date(original.get("started_at")));
            repository.saveAndFlush(task);
            for(var reminder:state.path("reminders")) if(reminder.path("status").asText().equals("PENDING")) {
                UUID id=UUID.fromString(reminder.path("id").asText());
                int restored=jdbc.update("UPDATE reminders SET status='PENDING' WHERE id=? AND task_id=? AND status='CANCELLED'",id,taskId);
                if(restored!=1) throw new ValidationException("Напоминания уже изменились, открой задачу для исправления");
                var at=date(reminder.get("fire_at"));
                if(at!=null && at.isAfter(OffsetDateTime.now(clock))) notifications.scheduleReminder(user,taskId,id,task.getTitle(),at,task.getDeadline());
            }
            audit.record(user,taskId,AuditEventType.STATUS_CHANGED,Map.of("status",task.getStatus().name(),"reason","undo"));
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Invalid task operation",e); }
    }
    private OffsetDateTime date(com.fasterxml.jackson.databind.JsonNode value) { return value==null||value.isNull()?null:OffsetDateTime.parse(value.asText()); }
    private record Stored(String before,String after,OffsetDateTime expires,boolean undone) {}
}
