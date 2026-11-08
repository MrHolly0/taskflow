package ru.taskflow.app;

import ru.taskflow.assistant.api.AssistantActionType;
import java.time.*;
import java.util.*;
import static ru.taskflow.app.PlanningLargeContextCases.*;

final class PlanningLargeContextControlCases {
    private PlanningLargeContextControlCases() {}
    private static Expected action(AssistantActionType type, String key, Map<String,Object> fields) {return new Expected(type,key,null,fields);}
    private static Scenario normal(String id, String request, List<Expected> expected, Set<String> hidden) {
        return new Scenario(id,"CONTROL",request,expected,Control.ACTIONS,false,false,hidden,Set.of());
    }
    static List<Scenario> all(LocalDate day) {
        return List.of(
            normal("control-complete", "Проверка сметы по Янтарю уже закончена. Закрой именно это дело.",
                List.of(action(AssistantActionType.COMPLETE,"complete",Map.of())),Set.of("complete")),
            normal("control-cancel", "Больше не нужно забирать монитор из мастерской Вереск — отмени эту задачу.",
                List.of(action(AssistantActionType.CANCEL,"cancel",Map.of())),Set.of("cancel")),
            normal("control-deadline", "Сдвинь дедлайн передачи акта по проекту Факел на через три дня, 10:05.",
                List.of(action(AssistantActionType.RESCHEDULE,"reschedule",Map.of("new_deadline",at(day,3,10,5)))),Set.of("reschedule")),
            normal("control-offset-seven", "Для задачи оплаты аренды склада по договору Каскад поставь уведомление за 7 минут до срока.",
                List.of(action(AssistantActionType.REMIND,"relative",Map.of("reminder_at",at(day,20,17,38)))),Set.of("relative")),
            normal("control-offset-hours", "По договору Каскад нужно напомнить об оплате аренды склада за два часа до дедлайна.",
                List.of(action(AssistantActionType.REMIND,"relative",Map.of("reminder_at",at(day,20,15,45)))),Set.of("relative")),
            normal("control-offset-days", "Уведоми об оплате аренды склада по договору Каскад за двое суток до установленного срока.",
                List.of(action(AssistantActionType.REMIND,"relative",Map.of("reminder_at",at(day,18,17,45)))),Set.of("relative")),
            new Scenario("control-offset-visible","CONTROL","По задаче оплаты аренды склада по договору Каскад напомни за 43 минуты до дедлайна.",
                List.of(action(AssistantActionType.REMIND,"relative",Map.of("reminder_at",at(day,1,17,2)))),Control.ACTIONS,true,false,Set.of(),Set.of("relative")),
            normal("control-absolute", "О заказе фильтра для очистителя Риф напомни послезавтра в 07:55. Дату самой задачи не меняй.",
                List.of(action(AssistantActionType.REMIND,"remind",Map.of("reminder_at",at(day,2,7,55)))),Set.of("remind")),
            normal("control-create-offset", "Добавь новое дело: проверить сигнализацию в лаборатории завтра в 10:25, с напоминанием за 45 минут.",
                List.of(new Expected(AssistantActionType.CREATE,null,"сигнализац",Map.of("deadline",at(day,1,10,25),"reminder_at",at(day,1,9,40)))),Set.of()),
            normal("control-reschedule-offset", "Срок задачи передачи акта по проекту Факел сдвинь на послезавтра, 16:20, и напомни за полтора часа до нового срока.",
                List.of(action(AssistantActionType.RESCHEDULE,"reschedule",Map.of("new_deadline",at(day,2,16,20))),
                        action(AssistantActionType.REMIND,"reschedule",Map.of("reminder_at",at(day,2,14,50)))),Set.of("reschedule")),
            normal("control-group", "У всех дел группы Север, срок которых уже прошёл, поставь дедлайн через три дня в 12:35. Будущие дела не трогай.",
                List.of(action(AssistantActionType.RESCHEDULE,"mass1",Map.of("new_deadline",at(day,3,12,35))),
                        action(AssistantActionType.RESCHEDULE,"mass2",Map.of("new_deadline",at(day,3,12,35))),
                        action(AssistantActionType.RESCHEDULE,"mass3",Map.of("new_deadline",at(day,3,12,35)))),Set.of()),
            new Scenario("control-ambiguity","CONTROL","Сдвинь срок отчёта по оценке оборудования на послезавтра в 11:15.",
                List.of(),Control.CLARIFICATION,false,false,Set.of(),Set.of()),
            new Scenario("control-overflow","CONTROL","Все дела из группы Гранит отметь как выполненные, целиком.",
                List.of(),Control.SELECTION_LIMIT,false,true,Set.of(),Set.of()),
            new Scenario("control-no-deadline","CONTROL","Напомни о заказе фильтра для очистителя Риф за 25 минут до срока задачи.",
                List.of(),Control.CLARIFICATION,false,false,Set.of("remind"),Set.of()),
            normal("control-voice-create", "Завтро атнести справку в афтосервис.",
                List.of(new Expected(AssistantActionType.CREATE,null,"автосервис",Map.of("deadline",at(day,1,9,0)))),Set.of()),
            normal("control-voice-complete", "Я проверил смеду по проекту Янтарь, это уже сделано.",
                List.of(action(AssistantActionType.COMPLETE,"complete",Map.of())),Set.of("complete")),
            normal("control-priority", "У задачи проверки гарантии на принтер Вега сделай приоритет срочным.",
                List.of(action(AssistantActionType.UPDATE,"update",Map.of("priority","URGENT"))),Set.of("update")),
            normal("control-two-priorities", "Обнови приоритеты: у задачи проверки сметы по проекту Янтарь — низкий, у проверки гарантии на принтер Вега — срочный.",
                List.of(action(AssistantActionType.UPDATE,"complete",Map.of("priority","LOW")),
                        action(AssistantActionType.UPDATE,"update",Map.of("priority","URGENT"))),Set.of("complete","update")),
            normal("control-mixed", "Смета по Янтарю проверена — закрой задачу. А передачу акта по Факелу перенеси на завтра в 13:05.",
                List.of(action(AssistantActionType.COMPLETE,"complete",Map.of()),
                        action(AssistantActionType.RESCHEDULE,"reschedule",Map.of("new_deadline",at(day,1,13,5)))),Set.of("complete","reschedule"))
        );
    }
}
