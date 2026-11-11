package ru.taskflow.telegram.application;

import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.dto.ActionOutcome;
import ru.taskflow.shared.widgets.*;
import ru.taskflow.task.api.TaskOperationService;
import ru.taskflow.task.api.TaskService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ru.taskflow.user.api.UserService;

@Component
public class WidgetReceiptProjection {
    private final AssistantService assistant;
    private final TaskOperationService operations;
    private final TaskService tasks;
    private final UserService users;
    private final Clock clock;
    public WidgetReceiptProjection(AssistantService assistant,TaskOperationService operations,TaskService tasks,UserService users,Clock clock) {
        this.assistant=assistant;this.operations=operations;this.tasks=tasks;this.users=users;this.clock=clock;
    }
    public List<ActionOutcome> successes(UUID user,UUID proposal) {
        return assistant.result(user,proposal).outcomes().stream().filter(a->a.success()).toList();
    }
    public ActionOutcome current(UUID user,UUID proposal,int page) {
        var actions=successes(user,proposal);
        if(page<0 || page>=actions.size()) throw new IllegalArgumentException("Нет такого действия");
        return actions.get(page);
    }
    public UUID taskId(UUID user,UUID proposal,int page) {
        var all=successes(user,proposal);return page>=0&&page<all.size()?all.get(page).taskId():null;
    }
    public WidgetState render(UUID user,UUID proposal,int requestedPage) {
        var result=assistant.result(user,proposal);var all=result.outcomes().stream().filter(a->a.success()).toList();
        if(all.isEmpty()) return new WidgetState("receipt","Попробуй переформулировать запрос",List.of())
                .finish("Изменения не сохранены","Не получилось выполнить действие");
        int page=Math.clamp(requestedPage,0,all.size()-1);var action=all.get(page);
        var undo=action.undoId()==null?java.util.Optional.<ru.taskflow.task.api.dto.TaskOperationReceipt>empty():operations.find(user,action.undoId());
        boolean undone=undo.map(r->r.undone()).orElse(false);
        boolean available=undo.filter(r->!r.undone()&&r.expiresAt().isAfter(OffsetDateTime.now(clock))).isPresent();
        boolean completion=undo.map(r->r.kind().equals("COMPLETE")).orElse(false);
        String title=action.summary();var rows=new ArrayList<List<String>>();
        String taskId=action.taskId()==null?null:action.taskId().toString();
        if(undone && undo.get().kind().equals("CREATE")) taskId=null;
        else if(action.taskId()!=null) {
            var task=tasks.findById(user,action.taskId());title=task.title();
            if(!completion || undone) rows.add(List.of("Срок",WidgetTemplate.time(task.deadline(),users.getTimezone(user),clock)));
            var reminder=task.reminders().stream().filter(r->r.status().equals("PENDING")&&r.fireAt().isAfter(OffsetDateTime.now(clock)))
                    .min(java.util.Comparator.comparing(r->r.fireAt()));
            if(!completion || undone) rows.add(List.of("Напомнить",reminder.map(r->WidgetTemplate.time(r.fireAt(),users.getTimezone(user),clock)).orElse("Не назначено")));
            if(task.plannedDate()!=null && !completion) rows.add(List.of("День работы",task.plannedDate().atZoneSameInstant(users.getTimezone(user)).toLocalDate().toString()));
        }
        String heading=undone?"Действие отменено":undo.map(r->r.kind().equals("COMPLETE")?ru.taskflow.shared.engagement.CompletionFeedback.forOperation(r.id()):"Задача создана").orElse("Изменения сохранены");
        if(!undone && result.appliedCount()<result.totalCount()) heading="Сохранено частично";
        String status=result.appliedCount()<result.totalCount()?"Сохранено: "+result.appliedCount()+" из "+result.totalCount():null;
        if(undone) status=undo.get().kind().equals("CREATE")?"Создание отменено":"Предыдущий статус возвращён";
        return new WidgetState("receipt",WidgetTemplate.clip(title,64),rows).receipt(page,all.size(),available,taskId,heading,status);
    }
}
