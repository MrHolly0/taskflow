package ru.taskflow.notify.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface NotificationService {
    /**
     * fireAt — когда именно отправить, уже посчитано вызывающей стороной:
     * этот метод больше не выводит время напоминания из срока задачи (Б1/Б2
     * — сколько будет напоминаний и когда, решает планировщик над таблицей
     * reminders, не этот метод). deadline — только для текста напоминания
     * («Дедлайн: завтра в 9:00»), допускает null — у задачи может не быть
     * срока вовсе (Б2). reminderId клеймится на каждую созданную строку —
     * без него отмена одного напоминания (см. cancelReminderNotifications)
     * не отличила бы свои уведомления от уведомлений соседних напоминаний
     * той же задачи.
     */
    void scheduleReminder(UUID userId, UUID taskId, UUID reminderId, String title,
                          OffsetDateTime fireAt, OffsetDateTime deadline);

    void refreshTaskReminderDetails(UUID userId, UUID taskId, String title, OffsetDateTime deadline);

    void cancelTaskNotifications(UUID taskId);

    /**
     * Снятие ровно одного напоминания (А2) — не трогает уведомления,
     * запланированные другими напоминаниями той же задачи.
     */
    void cancelReminderNotifications(UUID reminderId);

    /**
     * Переносит запланированные напоминания на другую учётку. Адресат каждой
     * строки пересчитывается на идентичность to того же канала, если она
     * есть — иначе остаётся прежним (столбец NOT NULL, придумывать значение
     * нельзя).
     */
    int transferOwnership(UUID from, UUID to);
}
