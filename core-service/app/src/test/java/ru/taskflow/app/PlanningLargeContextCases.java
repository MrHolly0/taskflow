package ru.taskflow.app;

import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.task.api.TaskPriority;
import java.time.*;
import java.util.*;

final class PlanningLargeContextCases {
    static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    record Seed(String key, String title, String group, TaskPriority priority, OffsetDateTime deadline) {}
    record Expected(AssistantActionType type, String target, String titlePart, Map<String, Object> fields) {}
    enum Control { ACTIONS, CLARIFICATION, SELECTION_LIMIT }
    record Scenario(String id, String category, String request, List<Expected> expected, Control control,
                    boolean futureBackground, boolean overflow, Set<String> hidden, Set<String> visible) {}
    private PlanningLargeContextCases() {}

    static OffsetDateTime at(LocalDate day, int days, int hour, int minute) {
        return day.plusDays(days).atTime(hour, minute).atZone(ZONE).toOffsetDateTime();
    }
    static List<Seed> seeds(LocalDate day, Scenario scenario) {
        boolean visible = scenario.futureBackground();
        List<Seed> rows = new ArrayList<>(List.of(
                new Seed("visible", "Распечатать маршрут поездки в Тулу", "Личное", TaskPriority.MEDIUM, at(day, visible ? 6 : -60, 10, 0)),
                new Seed("complete", "Проверить смету по проекту Янтарь", "Работа", TaskPriority.MEDIUM, null),
                new Seed("cancel", "Забрать монитор из мастерской Вереск", "Личное", TaskPriority.MEDIUM, null),
                new Seed("reschedule", "Передать акт по проекту Факел", "Работа", TaskPriority.MEDIUM, at(day, 30, 17, 45)),
                new Seed("remind", "Заказать фильтр для очистителя Риф", "Дом", TaskPriority.MEDIUM, null),
                new Seed("relative", "Оплатить аренду склада по договору Каскад", "Работа", TaskPriority.MEDIUM, at(day, visible ? 1 : 20, 17, 45)),
                new Seed("update", "Проверить гарантию на принтер Вега", "Дом", TaskPriority.HIGH, null),
                new Seed("mass1", "Согласовать счёт по проекту Север", "Север", TaskPriority.MEDIUM, at(day, visible ? 5 : -1, 9, 0)),
                new Seed("mass2", "Передать спецификацию по проекту Север", "Север", TaskPriority.MEDIUM, at(day, visible ? 5 : -1, 11, 0)),
                new Seed("mass3", "Подписать накладную по проекту Север", "Север", TaskPriority.MEDIUM, at(day, visible ? 5 : -1, 13, 0)),
                new Seed("massFuture", "Получить образцы по проекту Север", "Север", TaskPriority.MEDIUM, at(day, 3, 12, 0)),
                new Seed("ambiguous1", "Подготовить отчёт по оценке оборудования для филиала А", "Работа", TaskPriority.MEDIUM, null),
                new Seed("ambiguous2", "Подготовить отчёт по оценке оборудования для филиала Б", "Работа", TaskPriority.MEDIUM, null)));
        if (scenario.overflow()) for (int i = 1; i <= 21; i++)
            rows.add(new Seed("overflow" + i, "Проверить комплект документов Гранит " + i, "Гранит", TaskPriority.MEDIUM, null));
        return List.copyOf(rows);
    }
    static Seed background(LocalDate day, Scenario scenario, int index) {
        String[] jobs = {"Проверить смету", "Согласовать договор", "Подготовить отчёт", "Передать акт", "Заказать расходные материалы",
                "Проверить гарантию", "Оплатить счёт", "Обновить документацию", "Сверить показания счётчика", "Подготовить презентацию"};
        String[] projects = {"Орион", "Альтаир", "Маяк", "Спектр", "Лотос", "Кварц", "Атлас", "Полюс", "Радуга", "Сфера"};
        String[] groups = {"Работа", "Дом", "Личное", "Учёба"};
        int slot = index % 10;
        OffsetDateTime deadline = slot == 9 ? null : scenario.futureBackground()
                ? at(day, 2 + index % 7, 12, index % 2 == 0 ? 0 : 30)
                : at(day, slot < 4 ? -2 - index % 20 : 1 + index % 7, 12, index % 2 == 0 ? 0 : 30);
        String title = jobs[index % jobs.length] + " по проекту " + projects[(index / jobs.length) % projects.length]
                + " — этап " + (index / 100 + 1);
        return new Seed("background" + index, title, groups[index % groups.length], TaskPriority.MEDIUM, deadline);
    }
    private static Expected action(AssistantActionType type, String target, Map<String, Object> fields) {
        return new Expected(type, target, null, fields);
    }
    private static Expected create(String title, Map<String, Object> fields) {
        return new Expected(AssistantActionType.CREATE, null, title, fields);
    }
    private static Scenario scenario(String id, String category, String request, List<Expected> expected,
                                     Set<String> hidden, Set<String> visible) {
        return new Scenario(id, category, request, expected, Control.ACTIONS, false, false, hidden, visible);
    }
    static List<Scenario> all(LocalDate day) {
        var reschedule = action(AssistantActionType.RESCHEDULE, "reschedule", Map.of("new_deadline", at(day, 1, 17, 25)));
        var relative = action(AssistantActionType.REMIND, "relative", Map.of("reminder_at", at(day, 20, 17, 10)));
        return List.of(
                scenario("create-dates", "CREATE", "Добавь две новые задачи: завтра в 09:20 получить пропуск в лабораторию Ладога, а послезавтра в 11:35 проверить проектор в аудитории 418.",
                        List.of(create("пропуск", Map.of("deadline", at(day, 1, 9, 20))), create("проектор", Map.of("deadline", at(day, 2, 11, 35)))), Set.of(), Set.of()),
                scenario("create-reminder", "REMINDER", "Добавь новую задачу: посетить выставку Геометрия послезавтра в 14:10. Напомни о ней в тот же день в 13:40.",
                        List.of(create("геометри", Map.of("deadline", at(day, 2, 14, 10), "reminder_at", at(day, 2, 13, 40)))), Set.of(), Set.of()),
                scenario("complete-visible", "COMPLETE", "Отметь выполненной задачу «Распечатать маршрут поездки в Тулу».",
                        List.of(action(AssistantActionType.COMPLETE, "visible", Map.of())), Set.of(), Set.of("visible")),
                scenario("complete-hidden", "COMPLETE", "Задачу «Проверить смету по проекту Янтарь» я выполнил, отметь завершённой.",
                        List.of(action(AssistantActionType.COMPLETE, "complete", Map.of())), Set.of("complete"), Set.of()),
                scenario("cancel-hidden", "CANCEL", "Отмени задачу «Забрать монитор из мастерской Вереск»: ремонт больше не нужен.",
                        List.of(action(AssistantActionType.CANCEL, "cancel", Map.of())), Set.of("cancel"), Set.of()),
                scenario("reschedule-hidden", "DEADLINE", "Перенеси задачу «Передать акт по проекту Факел» на завтра в 17:25.",
                        List.of(reschedule), Set.of("reschedule"), Set.of()),
                scenario("remind-absolute", "REMINDER", "Напомни о задаче «Заказать фильтр для очистителя Риф» завтра в 08:40, срок задачи не меняй.",
                        List.of(action(AssistantActionType.REMIND, "remind", Map.of("reminder_at", at(day, 1, 8, 40)))), Set.of("remind"), Set.of()),
                scenario("remind-relative-hidden", "RELATIVE_REMINDER", "Поставь напоминание за 35 минут до срока задачи «Оплатить аренду склада по договору Каскад». Сам срок не меняй.",
                        List.of(relative), Set.of("relative"), Set.of()),
                new Scenario("remind-relative-visible", "RELATIVE_REMINDER", "Поставь напоминание за 35 минут до срока задачи «Оплатить аренду склада по договору Каскад». Сам срок не меняй.",
                        List.of(action(AssistantActionType.REMIND, "relative", Map.of("reminder_at", at(day, 1, 17, 10)))), Control.ACTIONS, true, false, Set.of(), Set.of("relative")),
                scenario("update-group", "UPDATE", "Перемести задачу «Проверить гарантию на принтер Вега» в группу Личное. Остальные поля не меняй.",
                        List.of(action(AssistantActionType.UPDATE, "update", Map.of("group", "Личное"))), Set.of("update"), Set.of()),
                scenario("mixed", "MIXED", "Задачу «Проверить смету по проекту Янтарь» выполнил. Задачу «Забрать монитор из мастерской Вереск» отмени. «Передать акт по проекту Факел» перенеси на послезавтра в 16:15. Для задачи «Проверить гарантию на принтер Вега» установи низкий приоритет.",
                        List.of(action(AssistantActionType.COMPLETE, "complete", Map.of()), action(AssistantActionType.CANCEL, "cancel", Map.of()),
                                action(AssistantActionType.RESCHEDULE, "reschedule", Map.of("new_deadline", at(day, 2, 16, 15))),
                                action(AssistantActionType.UPDATE, "update", Map.of("priority", "LOW"))), Set.of("complete", "cancel", "reschedule", "update"), Set.of()),
                scenario("group-overdue", "BULK", "Перенеси сроки всех просроченных задач группы Север на послезавтра в 15:50. Остальные задачи не меняй.",
                        List.of(action(AssistantActionType.RESCHEDULE, "mass1", Map.of("new_deadline", at(day, 2, 15, 50))),
                                action(AssistantActionType.RESCHEDULE, "mass2", Map.of("new_deadline", at(day, 2, 15, 50))),
                                action(AssistantActionType.RESCHEDULE, "mass3", Map.of("new_deadline", at(day, 2, 15, 50)))), Set.of(), Set.of()),
                new Scenario("ambiguous", "CLARIFICATION", "Перенеси отчёт по оценке оборудования на завтра в 10:30.", List.of(), Control.CLARIFICATION, false, false, Set.of(), Set.of()),
                new Scenario("selection-limit", "SAFETY_LIMIT", "Заверши все задачи группы Гранит.", List.of(), Control.SELECTION_LIMIT, false, true, Set.of(), Set.of()));
    }
}
