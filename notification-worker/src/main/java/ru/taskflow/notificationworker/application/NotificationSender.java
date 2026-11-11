package ru.taskflow.notificationworker.application;

public interface NotificationSender {

    boolean supports(String channel);

    void sendTaskReminder(String destination, String title, String deadline, String timezone);
    default void sendEngagement(String destination, String kind, EngagementPayload payload) {
        throw new NotificationDeliveryException("UNSUPPORTED_CHANNEL", true, null);
    }
}
