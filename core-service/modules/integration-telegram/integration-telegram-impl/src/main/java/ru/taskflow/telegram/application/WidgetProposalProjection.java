package ru.taskflow.telegram.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.shared.widgets.WidgetState;
import ru.taskflow.shared.widgets.WidgetTemplate;
import ru.taskflow.user.api.UserService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.MessageDigest;

@Component
public class WidgetProposalProjection {
    private final UserService users;
    private final ObjectMapper mapper;
    private final Clock clock;
    public WidgetProposalProjection(UserService users, ObjectMapper mapper, Clock clock) {
        this.users=users; this.mapper=mapper; this.clock=clock;
    }
    public WidgetState render(UUID userId, Proposal proposal, int requestedPage) {
        int page=Math.clamp(requestedPage,0,proposal.actions().size()-1);
        var action=proposal.actions().get(page);
        var payload=action.payload();
        var zone=users.getTimezone(userId);
        var rows=new ArrayList<List<String>>();
        rows.add(List.of("Действие",switch(action.type()) {
            case CREATE -> "Создать задачу"; case COMPLETE -> "Завершить"; case CANCEL -> "Отменить";
            case RESCHEDULE -> "Перенести срок"; case UPDATE -> "Обновить"; case REMIND -> "Напомнить";
        }));
        if (action.type()==AssistantActionType.CREATE && payload.get("planned_date")!=null) {
            rows.clear();
            String day;
            try { day=OffsetDateTime.parse(payload.get("planned_date").toString()).atZoneSameInstant(zone)
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM",java.util.Locale.forLanguageTag("ru"))); }
            catch (RuntimeException e) { day="Уточни в Мунине"; }
            rows.add(List.of("День работы",day));
        }
        Object deadline=payload.get(action.type()==AssistantActionType.RESCHEDULE?"new_deadline":"deadline");
        if (action.type()==AssistantActionType.CREATE || action.type()==AssistantActionType.RESCHEDULE)
            rows.add(List.of("Срок",deadline==null?"Без срока":time(deadline,zone)));
        Object reminder=payload.get("reminder_at");
        String reminderText=reminder==null?"Без изменения":time(reminder,zone);
        if (action.type()==AssistantActionType.CREATE && reminder==null) {
            reminderText=Boolean.TRUE.equals(payload.get("no_reminder_needed")) || deadline==null?"Не назначено":
                    "По настройкам: за "+users.getSettings(userId).defaultReminderMinutes()+" мин";

        }
        if (action.type()==AssistantActionType.CREATE || action.type()==AssistantActionType.REMIND)
            rows.add(List.of("Напомнить",reminderText));
        if (action.type()==AssistantActionType.UPDATE) {
            for (String field:List.of("title","description","priority","group")) if (payload.get(field)!=null)
                rows.add(List.of(switch(field) { case "title" -> "Название"; case "description" -> "Описание";
                    case "priority" -> "Приоритет"; default -> "Группа"; },WidgetTemplate.clip(payload.get(field).toString(),48)));
        }
        String title=action.type()==AssistantActionType.CREATE?String.valueOf(payload.getOrDefault("title",action.summary())):action.summary();
        String warning=proposal.exclusive()?"Выбери один вариант":proposal.hasRejections()?"Часть запроса не разобрана — открой Мунин":null;
        if (proposal.exclusive() && proposal.hasRejections()) warning="Есть неразобранные части — открой Мунин";
        var state=new WidgetState("proposal",WidgetTemplate.clip(title,64),rows)
                .proposal(page,proposal.actions().size(),(int)proposal.actions().stream().filter(a->a.accepted()).count(),action.accepted(),warning)
                .fingerprint(fingerprint(proposal));
        if (proposal.status()!=ProposalStatus.PENDING) {
            return state.finish(switch(proposal.status()) {
                case APPLIED -> "Изменения сохранены"; case PARTIALLY_APPLIED -> "Сохранено частично";
                case REJECTED -> "Предложение отклонено"; case EXPIRED -> "Время подтверждения истекло";
                default -> "Нужна проверка";
            },proposal.status()==ProposalStatus.PARTIALLY_APPLIED?"Проверь результат в Мунине":"Можно рассказать о следующем деле");
        }
        if (proposal.expiresAt().isBefore(OffsetDateTime.now(clock))) return state.finish("Время подтверждения истекло","Расскажи Мунину ещё раз");
        return state;
    }
    private String time(Object value,java.time.ZoneId zone) {
        try { return WidgetTemplate.time(OffsetDateTime.parse(value.toString()),zone,clock); }
        catch (RuntimeException e) { return "Уточни в Мунине"; }
    }
    private String fingerprint(Proposal proposal) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                mapper.writeValueAsBytes(Map.of("actions",proposal.actions(),"exclusive",proposal.exclusive(),
                        "status",proposal.status(),"expires",proposal.expiresAt().toString()))),0,8); }
        catch (Exception e) { throw new IllegalStateException("INVALID_PROPOSAL_STATE",e); }
    }
}
