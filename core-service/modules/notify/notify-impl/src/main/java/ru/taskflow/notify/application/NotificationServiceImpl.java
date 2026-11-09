package ru.taskflow.notify.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.notify.api.NotificationChannel;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.notify.infrastructure.persistence.PushSubscriptionRepository;
import ru.taskflow.notify.infrastructure.persistence.ScheduledNotificationJpaEntity;
import ru.taskflow.notify.infrastructure.persistence.ScheduledNotificationRepository;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UserSettingsDto;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сервис управления напоминаниями и уведомлениями.
 *
 * Планирует отправку напоминаний о задачах, управляет расписанием уведомлений,
 * отправляемых notification-worker: очередь в scheduled_notifications и
 * периодический опрос по времени срабатывания, без внешнего планировщика.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final ScheduledNotificationRepository scheduledNotificationRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void scheduleReminder(UUID userId, UUID taskId, UUID reminderId, String title,
                                  OffsetDateTime fireAt, OffsetDateTime deadline) {
        OffsetDateTime now = OffsetDateTime.now();
        if (fireAt.isBefore(now)) {
            log.debug("Fire time is in the past, skipping notification for task: {}", taskId);
            return;
        }

        UserSettingsDto settings = userService.getSettings(userId);
        if (!settings.notificationsEnabled()) {
            log.debug("Notifications disabled by user, skipping notification for task: {}", taskId);
            return;
        }

        // Канал участвует, только если включён переключателем И у пользователя
        // есть привязанная идентичность этого канала — выключенный переключатель
        // не должен даже смотреть на идентичность, а привязка без переключателя
        // не должна слать. Собираем адресатов до вычисления таймзоны/срока —
        // если участвующих каналов нет вообще, не тратим лишний вызов на них.
        //
        // WEB_PUSH — не идентичность, а произвольное число подписок на
        // устройства: один пользователь может получить напоминание сразу на
        // рабочий компьютер и телефон, поэтому у канала может быть несколько
        // адресатов, а не один.
        Map<NotificationChannel, List<String>> destinations = new EnumMap<>(NotificationChannel.class);
        for (IdentityProvider provider : IdentityProvider.values()) {
            if (!channelEnabled(settings, provider)) {
                continue;
            }
            userService.findExternalId(userId, provider)
                    .ifPresent(id -> destinations.put(NotificationChannel.valueOf(provider.name()), List.of(id)));
        }
        if (settings.notifyPush()) {
            List<String> pushSubscriptionIds = pushSubscriptionRepository.findByUserId(userId).stream()
                    .map(subscription -> subscription.getId().toString())
                    .toList();
            if (!pushSubscriptionIds.isEmpty()) {
                destinations.put(NotificationChannel.WEB_PUSH, pushSubscriptionIds);
            }
        }
        if (destinations.isEmpty()) {
            log.warn("No eligible notification channel (toggle + identity) for user: {}", userId);
            return;
        }

        ZoneId timezone = userService.getTimezone(userId);
        String payload = buildPayload(title, deadline, timezone);

        // Строка на адресата, а не веер внутри одной строки: sent и retry_count
        // живут в строке, и только так отказ одного канала или одной подписки
        // (например письмо не ушло, или одно устройство отписалось) не мешает
        // доставке по остальным.
        destinations.forEach((channel, ids) ->
                ids.forEach(destination -> scheduleForChannel(userId, taskId, reminderId, channel, destination, fireAt, payload)));
    }

    @Override
    @Transactional
    public void refreshTaskReminderDetails(UUID userId, UUID taskId, String title, OffsetDateTime deadline) {
        scheduledNotificationRepository.updateUnsentPayload(userId, taskId,
                buildPayload(title, deadline, userService.getTimezone(userId)));
    }

    private boolean channelEnabled(UserSettingsDto settings, IdentityProvider channel) {
        return switch (channel) {
            case TELEGRAM -> settings.notifyTelegram();
            case EMAIL -> settings.notifyEmail();
            // Телефон — только вход, не канал уведомлений: на номер ничего не шлём.
            case PHONE -> false;
        };
    }

    private void scheduleForChannel(UUID userId, UUID taskId, UUID reminderId, NotificationChannel channel,
                                     String destination, OffsetDateTime fireAt, String payload) {
        var notification = new ScheduledNotificationJpaEntity();
        notification.setUserId(userId);
        notification.setTaskId(taskId);
        notification.setReminderId(reminderId);
        notification.setChannel(channel);
        notification.setDestination(destination);
        notification.setFireAt(fireAt);
        notification.setPayloadType("TASK_REMINDER");
        notification.setPayload(payload);
        notification.setSent(false);
        notification.setRetryCount(0);

        scheduledNotificationRepository.save(notification);
        log.info("Scheduled {} notification for task {} at {}", channel, taskId, fireAt);
    }

    @Override
    @Transactional
    public void cancelTaskNotifications(UUID taskId) {
        scheduledNotificationRepository.deleteUnsentByTaskId(taskId);
        log.debug("Cancelled unsent notifications for task: {}", taskId);
    }

    @Override
    @Transactional
    public void cancelReminderNotifications(UUID reminderId) {
        scheduledNotificationRepository.deleteUnsentByReminderId(reminderId);
        log.debug("Cancelled unsent notifications for reminder: {}", reminderId);
    }

    @Override
    @Transactional
    public int transferOwnership(UUID from, UUID to) {
        String telegramDestination = userService.findExternalId(to, IdentityProvider.TELEGRAM).orElse(null);
        String emailDestination = userService.findExternalId(to, IdentityProvider.EMAIL).orElse(null);
        return scheduledNotificationRepository.reassignOwner(from, to, telegramDestination, emailDestination);
    }

    private String buildPayload(String title, OffsetDateTime deadline, ZoneId timezone) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("taskTitle", title);
            // null — у задачи нет срока (Б2): DeadlineHumanizer и оба отправителя
            // в notification-worker уже умеют опускать строку дедлайна для null.
            payload.put("deadline", deadline != null ? deadline.toString() : null);
            payload.put("timezone", timezone.getId());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize notification payload", e);
            return "{}";
        }
    }
}
