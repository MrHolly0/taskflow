package ru.taskflow.telegram.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.notify.api.EngagementService;
import ru.taskflow.shared.widgets.*;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.user.api.UserService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class WidgetActions {
    private final WidgetStore store;
    private final JdbcTemplate jdbc;
    private final TaskService tasks;
    private final AssistantService assistant;
    private final EngagementService engagement;
    private final WidgetProposalProjection projection;
    private final UserService users;
    private final Clock clock;
    private final WidgetReceiptProjection receipts;
    private final ru.taskflow.task.api.TaskOperationService operations;
    public WidgetActions(WidgetStore store,JdbcTemplate jdbc,TaskService tasks,AssistantService assistant,
            EngagementService engagement,WidgetProposalProjection projection,UserService users,Clock clock,WidgetReceiptProjection receipts,
            ru.taskflow.task.api.TaskOperationService operations) {
        this.store=store;this.jdbc=jdbc;this.tasks=tasks;this.assistant=assistant;
        this.engagement=engagement;this.projection=projection;this.users=users;this.clock=clock;this.receipts=receipts;this.operations=operations;
    }
    @Transactional(timeout=10)
    public WidgetState act(UUID userId,long chatId,long messageId,String data) {
        return act(userId,chatId,messageId,data,null);
    }
    @Transactional(timeout=10)
    public WidgetState act(UUID userId,long chatId,long messageId,String data,java.time.Instant deadline) {
        requireDeadline(deadline);
        if (deadline!=null) jdbc.execute("SET LOCAL lock_timeout='1000ms'");
        var token=Token.parse(data);
        var preview=store.find(token.id(),userId).orElseThrow(()->new IllegalArgumentException("Карточка недоступна"));
        if (preview.chatId()!=chatId || !Objects.equals(preview.messageId(),messageId))
            throw new IllegalArgumentException("Карточка недоступна");
        // Порядок совпадает с изменением задачи и ответом на проверку результата.
        UUID lockedTask=preview.kind().equals("RECEIPT")?receipts.taskId(userId,preview.referenceId(),preview.page()):preview.taskId();
        if (lockedTask!=null) jdbc.query("SELECT id FROM tasks WHERE id=? AND user_id=? FOR UPDATE",
                (rs,row)->rs.getObject(1,UUID.class),lockedTask,userId);
        var context=store.lock(token.id(),userId).orElseThrow();
        if (context.state().terminal()) return context.state();
        if (context.expiresAt().isBefore(OffsetDateTime.now(clock)))
            return save(context,context.state().finish("Карточка устарела","Открой актуальную задачу в Мунине"),context.page());
        requireDeadline(deadline);
        if (context.revision()!=token.revision()) { store.enqueueSync(context); return context.state(); }
        if (token.action().equals("details")) return store.save(context,context.state(),context.page(),true).state();
        if(context.kind().equals("RECEIPT")) {
            int page=context.page();
            if(token.action().matches("pg[0-9]{1,4}")) {
                page=Integer.parseInt(token.action().substring(2));receipts.current(userId,context.referenceId(),page);
            } else if(token.action().equals("undo")) {
                var outcome=receipts.current(userId,context.referenceId(),page);
                if(outcome.undoId()==null) throw new IllegalArgumentException("Отмена недоступна");
                operations.undo(userId,outcome.undoId());
            } else throw new IllegalArgumentException("Неизвестное действие");
            return save(context,receipts.render(userId,context.referenceId(),page),page);
        }
        if (context.kind().equals("PROPOSAL")) return proposal(context,token.action(),deadline);
        if (!List.of("done","working","later","mute").contains(token.action())) throw new IllegalArgumentException("Неизвестное действие");
        if (context.kind().equals("ENGAGEMENT")) {
            String action=token.action();
            if ((context.state().kind().equals("return"))!=action.equals("mute")) throw new IllegalArgumentException("Неизвестное действие");
            requireDeadline(deadline);
            var reply=engagement.reply(userId,context.referenceId(),action);
            if (!reply.terminal()) throw new IllegalArgumentException(clean(reply.text()));
            String heading=switch(java.util.Objects.toString(reply.outcome(),"")) {
                case "done" -> ru.taskflow.shared.engagement.CompletionFeedback.forOperation(context.referenceId()); case "working" -> "Продолжаем";
                case "later" -> "Напомню позже"; case "mute" -> "Приглашения выключены";
                case "stale" -> "Задача уже изменилась"; default -> "Ответ принят";
            };
            return save(context,context.state().finish(heading,clean(reply.text())),context.page());
        }
        if (token.action().equals("mute")) throw new IllegalArgumentException("Неизвестное действие");
        var current=jdbc.query("""
                SELECT t.status,t.deadline,t.planned_date,n.reminder_id,r.status AS reminder_status,r.chain_step,t.persistent_reminder
                FROM tasks t LEFT JOIN scheduled_notifications n ON n.id=? AND n.user_id=t.user_id AND n.task_id=t.id
                LEFT JOIN reminders r ON r.id=n.reminder_id AND r.task_id=t.id
                WHERE t.id=? AND t.user_id=? AND NOT t.is_deleted
                """,(rs,row)->new ReminderTarget(rs.getString("status"),rs.getObject("deadline",OffsetDateTime.class),
                rs.getObject("planned_date",OffsetDateTime.class),rs.getObject("reminder_id",UUID.class),rs.getString("reminder_status"),rs.getObject("chain_step",Integer.class),rs.getBoolean("persistent_reminder")),
                context.referenceId(),context.taskId(),userId);
        if (current.isEmpty() || !current.getFirst().valid(context.deadline(),OffsetDateTime.now(clock)))
            return save(context,context.state().finish("Задача уже изменилась","Открой её в Мунине"),0);
        var target=current.getFirst();
        requireDeadline(deadline);
        String result;
        switch (token.action()) {
            case "done" -> { tasks.complete(userId,context.taskId()); result="Одним делом меньше!"; }
            case "working" -> {
                tasks.update(userId,context.taskId(),new UpdateTaskRequest(null,null,null,TaskStatus.IN_PROGRESS,null,null,null,null,null));
                result="Задача в работе";
            }
            case "later" -> {
                var settings=users.getSettings(userId);
                if (!settings.notificationsEnabled() || !settings.notifyTelegram()) throw new IllegalArgumentException("Уведомления Telegram выключены в настройках");
                var at=OffsetDateTime.now(clock).plusHours(1);
                UUID reminderId=target.reminderId();
                if ("DISMISSED".equals(target.reminderStatus())) reminderId=jdbc.query(
                        "SELECT id FROM reminders WHERE task_id=? AND status='PENDING' AND chain_step IS NOT NULL ORDER BY fire_at LIMIT 1",
                        (rs,row)->rs.getObject(1,UUID.class),context.taskId()).stream().findFirst().orElse(null);
                if (reminderId!=null) tasks.snoozeReminder(userId,context.taskId(),reminderId,at);
                else tasks.scheduleReminder(userId,context.taskId(),at);
                result="Напомню "+WidgetTemplate.time(at,users.getTimezone(userId),clock);
            }
            default -> throw new IllegalArgumentException("Неизвестное действие");
        }
        return save(context,context.state().finish(token.action().equals("done")?"Одним делом меньше!":"Готово",result),0);
    }
    private WidgetState proposal(WidgetContext context,String action,java.time.Instant deadline) {
        var p=assistant.findById(context.userId(),context.referenceId());
        if (!p.hasActions()) return save(context,context.state().finish("Разбор изменился","Открой Мунин"),0);
        var fresh=projection.render(context.userId(),p,context.page());
        var old=context.state();
        if (fresh.terminal() || !Objects.equals(fresh.fingerprint(),old.fingerprint()) || !fresh.rows().equals(old.rows()))
            return save(context,fresh,context.page());
        requireDeadline(deadline);
        int page=context.page();
        if (action.matches("pg[0-9]{1,4}")) {
            page=Integer.parseInt(action.substring(2));
            if (page>=p.actions().size()) throw new IllegalArgumentException("Нет такого действия");
        } else if (action.equals("sel1") || action.equals("sel0")) {
            int ordinal=p.actions().get(page).ordinal();
            if (p.exclusive() && action.equals("sel1")) assistant.selectAlternative(context.userId(),p.id(),ordinal);
            else assistant.setActionAccepted(context.userId(),p.id(),ordinal,action.equals("sel1"));
        } else if (action.equals("a")) {
            if (p.actions().stream().noneMatch(a->a.accepted())) throw new IllegalArgumentException("Выбери хотя бы одно действие");
            var result=assistant.apply(context.userId(),p.id(),p.actions());
            var state=projection.render(context.userId(),assistant.findById(context.userId(),p.id()),page);
            return save(context,state.finish(switch(result.status()) { case APPLIED -> "Изменения сохранены"; case PARTIALLY_APPLIED -> "Сохранено частично";
                        case EXPIRED -> "Время подтверждения истекло"; default -> "Изменения не сохранены"; },
                    "Сохранено: "+result.appliedCount()+" из "+result.totalCount()),page);
        } else if (action.equals("r")) assistant.reject(context.userId(),p.id());
        else throw new IllegalArgumentException("Неизвестное действие");
        return save(context,projection.render(context.userId(),assistant.findById(context.userId(),p.id()),page),page);
    }
    private void requireDeadline(java.time.Instant deadline) {
        if (deadline!=null && !clock.instant().isBefore(deadline)) throw new IllegalArgumentException("Время ответа истекло — нажми ещё раз");
    }
    private WidgetState save(WidgetContext context,WidgetState state,int page) { return store.save(context,state,page,context.expanded()).state(); }
    private String clean(String text) {
        return WidgetTemplate.clip(text.replaceFirst("^[^\\p{L}\\p{N}]+", "").replaceFirst("[.!]$", ""),110);
    }
    record ReminderTarget(String status,OffsetDateTime deadline,OffsetDateTime planned,UUID reminderId,String reminderStatus,Integer chainStep,boolean persistent) {
        boolean valid(OffsetDateTime snapshot,OffsetDateTime now) {
            return (status.equals("TODO")||status.equals("IN_PROGRESS"))
                    && (deadline==null?snapshot==null:snapshot!=null&&deadline.isEqual(snapshot))
                    && (planned==null||!planned.isAfter(now)) && reminderId!=null && ("PENDING".equals(reminderStatus) || ("DISMISSED".equals(reminderStatus) && chainStep!=null && persistent));
        }
    }
    public record Token(UUID id,long revision,String action) {
        public static Token parse(String value) {
            try {
                if (value==null || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>64) throw new IllegalArgumentException();
                var parts=value.split(":",-1);
                if (parts.length!=4 || !parts[0].equals("w") || !parts[2].matches("[0-9]{1,9}")
                        || !parts[3].matches("[a-z0-9]{1,8}")) throw new IllegalArgumentException();
                return new Token(UUID.fromString(parts[1]),Long.parseLong(parts[2]),parts[3]);
            } catch (RuntimeException e) { throw new IllegalArgumentException("Карточка недоступна"); }
        }
    }
}
