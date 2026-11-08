package ru.taskflow.app;

import ru.taskflow.assistant.api.AssistantActionType;
import java.time.LocalDate;
import java.util.*;
import static ru.taskflow.app.PlanningLargeContextCases.*;

final class PlanningLargeContextHoldoutCases {
    private PlanningLargeContextHoldoutCases() {}
    private static Expected action(AssistantActionType type, String target, Map<String,Object> fields) {
        return new Expected(type,target,null,fields);
    }
    private static Scenario scenario(String id, String request, List<Expected> actions, String... hidden) {
        return new Scenario(id,"NEW_WORDING",request,actions,Control.ACTIONS,false,false,Set.of(hidden),Set.of());
    }
    static List<Scenario> all(LocalDate day) {
        return List.of(
            scenario("new-complete","Со сметой проекта Янтарь разобрался, проверка завершена. Можно помечать сделанной.",
                List.of(action(AssistantActionType.COMPLETE,"complete",Map.of())),"complete"),
            scenario("new-cancel","Поездка за монитором в мастерскую Вереск отпала. Эту задачу отменяем.",
                List.of(action(AssistantActionType.CANCEL,"cancel",Map.of())),"cancel"),
            scenario("new-deadline","Передачу акта по Факелу ждут через четыре дня к 08:40. Поменяй срок только у этого дела.",
                List.of(action(AssistantActionType.RESCHEDULE,"reschedule",Map.of("new_deadline",at(day,4,8,40)))),"reschedule"),
            scenario("new-offset-minutes","Об аренде склада по Каскаду уведомить за 95 минут до установленного срока оплаты.",
                List.of(action(AssistantActionType.REMIND,"relative",Map.of("reminder_at",at(day,20,16,10)))),"relative"),
            scenario("new-offset-hours","Поставь сигнал об оплате аренды склада по договору Каскад за 18 часов до дедлайна.",
                List.of(action(AssistantActionType.REMIND,"relative",Map.of("reminder_at",at(day,19,23,45)))),"relative"),
            scenario("new-reschedule-offset","По акту проекта Факел: новый дедлайн через четыре дня в 12:50. Напоминание сделай за 20 минут до нового срока.",
                List.of(action(AssistantActionType.RESCHEDULE,"reschedule",Map.of("new_deadline",at(day,4,12,50))),
                        action(AssistantActionType.REMIND,"reschedule",Map.of("reminder_at",at(day,4,12,30)))),"reschedule"),
            scenario("new-priority","Проверку гарантии на принтер Вега пока можно отложить — назначь ей пониженный приоритет.",
                List.of(action(AssistantActionType.UPDATE,"update",Map.of("priority","LOW"))),"update"),
            scenario("new-create-two","Докупи лампу завтра к 09:20, а кабель — послезавтра к 12:15. Запиши два отдельных дела.",
                List.of(new Expected(AssistantActionType.CREATE,null,"ламп",Map.of("deadline",at(day,1,9,20))),
                        new Expected(AssistantActionType.CREATE,null,"кабел",Map.of("deadline",at(day,2,12,15))))),
            scenario("new-remind-absolute","Пусть завтра в 11:45 придёт уведомление о заказе фильтра для очистителя Риф. Срок у задачи оставь прежним.",
                List.of(action(AssistantActionType.REMIND,"remind",Map.of("reminder_at",at(day,1,11,45)))),"remind"),
            scenario("new-mixed","Смета Янтаря уже проверена, отметь её выполненной. Проверку гарантии принтера Вега перенеси в Личное.",
                List.of(action(AssistantActionType.COMPLETE,"complete",Map.of()),
                        action(AssistantActionType.UPDATE,"update",Map.of("group","Личное"))),"complete","update")
        );
    }
}
