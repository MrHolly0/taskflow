package ru.taskflow.notificationworker.application;

public interface NotificationSender {

    boolean supports(String channel);

    void sendTaskReminder(String destination, String title, String deadline, String timezone);
    default void sendTaskReminderCard(ru.taskflow.notificationworker.infrastructure.db.PendingNotification notification,
            String title,String deadline,String timezone) { sendTaskReminder(notification.destination(),title,deadline,timezone); }
    default void sendEngagement(ru.taskflow.notificationworker.infrastructure.db.PendingNotification notification,EngagementPayload payload) {
        sendEngagement(notification.destination(),notification.payloadType(),payload);
    }
    default void sendWidgetSync(ru.taskflow.notificationworker.infrastructure.db.PendingNotification notification) {
        throw new NotificationDeliveryException("UNSUPPORTED_CHANNEL",true,null);
    }
    default void sendEngagement(String destination, String kind, EngagementPayload payload) {
        throw new NotificationDeliveryException("UNSUPPORTED_CHANNEL", true, null);
    }
}
