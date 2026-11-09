package ru.taskflow.notify.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.notify.api.NotificationChannel;
import ru.taskflow.notify.infrastructure.persistence.PushSubscriptionJpaEntity;
import ru.taskflow.notify.infrastructure.persistence.PushSubscriptionRepository;
import ru.taskflow.notify.infrastructure.persistence.ScheduledNotificationJpaEntity;
import ru.taskflow.notify.infrastructure.persistence.ScheduledNotificationRepository;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UserSettingsDto;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * scheduleReminder больше не выводит момент отправки из deadline (это
 * теперь решает TaskReminderService, см. его тесты на А4/А6/Б2) — здесь
 * только адресация: какие каналы участвуют, кому шлём, что попадает в
 * payload. fireAt в этих тестах — любое безопасно-будущее время, его
 * конкретное значение проверяемому поведению не важно.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private ScheduledNotificationRepository scheduledNotificationRepository;
    @Mock
    private PushSubscriptionRepository pushSubscriptionRepository;
    @Mock
    private UserService userService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotificationServiceImpl notificationService;

    private final UUID userId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final OffsetDateTime fireAt = OffsetDateTime.now().plusDays(1);
    private final UUID reminderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationService = new NotificationServiceImpl(
                scheduledNotificationRepository, pushSubscriptionRepository, userService, objectMapper);
        lenient().when(userService.getSettings(userId)).thenReturn(defaultSettings());
        lenient().when(userService.getTimezone(userId)).thenReturn(ZoneId.of("Europe/Moscow"));
        lenient().when(pushSubscriptionRepository.findByUserId(any())).thenReturn(List.of());
    }

    @Test
    void refreshingTextDoesNotDeleteOrCreateNotificationRows() throws Exception {
        notificationService.refreshTaskReminderDetails(userId, taskId, "Новое название", fireAt.plusDays(1));
        var payload = ArgumentCaptor.forClass(String.class);
        verify(scheduledNotificationRepository).updateUnsentPayload(org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.eq(taskId), payload.capture());
        assertThat(objectMapper.readTree(payload.getValue()).path("taskTitle").asText()).isEqualTo("Новое название");
        verify(scheduledNotificationRepository, never()).save(any());
        verify(scheduledNotificationRepository, never()).deleteUnsentByTaskId(any());
        verify(scheduledNotificationRepository, never()).deleteUnsentByReminderId(any());
    }

    @Test
    void scheduleReminder_createsOneRowPerChannelWhenBothIdentitiesExist() {
        // Строка на канал, не веер в одной: отказ одного не должен помешать другому.
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.of("user@example.com"));

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", fireAt, fireAt);

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository, times(2)).save(captor.capture());

        List<ScheduledNotificationJpaEntity> saved = captor.getAllValues();
        assertThat(saved).extracting(ScheduledNotificationJpaEntity::getChannel)
                .containsExactlyInAnyOrder(NotificationChannel.TELEGRAM, NotificationChannel.EMAIL);
        assertThat(saved).filteredOn(n -> n.getChannel() == NotificationChannel.TELEGRAM)
                .extracting(ScheduledNotificationJpaEntity::getDestination).containsExactly("12345");
        assertThat(saved).filteredOn(n -> n.getChannel() == NotificationChannel.EMAIL)
                .extracting(ScheduledNotificationJpaEntity::getDestination).containsExactly("user@example.com");
        assertThat(saved).extracting(ScheduledNotificationJpaEntity::getFireAt).containsOnly(fireAt);
    }

    @Test
    void scheduleReminder_createsOnlyTelegramRowWhenNoEmailIdentity() {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.empty());

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", fireAt, fireAt);

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository).save(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo(NotificationChannel.TELEGRAM);
    }

    @Test
    void scheduleReminder_stampsUserTimezoneIntoPayload() throws Exception {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.empty());
        when(userService.getTimezone(userId)).thenReturn(ZoneId.of("Asia/Yekaterinburg"));

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", fireAt, fireAt);

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository).save(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(captor.getValue().getPayload(), Map.class);
        assertThat(payload).containsEntry("timezone", "Asia/Yekaterinburg");
        assertThat(captor.getValue().getDestination()).isEqualTo("12345");
    }

    @Test
    void scheduleReminder_deadlineNull_payloadCarriesNullDeadlineWithoutFailing() throws Exception {
        // Б2: напоминание без срока задачи — deadline не участвует в фактическом
        // времени отправки (fireAt) и допускает null в тексте напоминания.
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.empty());

        notificationService.scheduleReminder(userId, taskId, reminderId, "позвонить маме", fireAt, null);

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository).save(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(captor.getValue().getPayload(), HashMap.class);
        assertThat(payload).containsKey("deadline");
        assertThat(payload.get("deadline")).isNull();
        assertThat(captor.getValue().getFireAt()).isEqualTo(fireAt);
    }

    @Test
    void scheduleReminder_usesTimezoneFromUserService_notHardcoded() {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.empty());
        when(userService.getTimezone(userId)).thenReturn(ZoneId.of("Europe/Kaliningrad"));

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", fireAt, fireAt);

        verify(userService).getTimezone(userId);
    }

    @Test
    void scheduleReminder_skipsWhenNoIdentityAtAll() {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.empty());
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.empty());

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", fireAt, fireAt);

        verify(scheduledNotificationRepository, never()).save(any());
        verify(userService, never()).getTimezone(any());
    }

    @Test
    void scheduleReminder_fireAtInPast_doesNotSchedule() {
        OffsetDateTime pastFireAt = OffsetDateTime.now().minusMinutes(5);

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", pastFireAt, pastFireAt);

        verify(scheduledNotificationRepository, never()).save(any());
        verify(userService, never()).getSettings(any());
    }

    @Test
    void cancelTaskNotifications_deletesUnsentByTaskId() {
        notificationService.cancelTaskNotifications(taskId);

        verify(scheduledNotificationRepository).deleteUnsentByTaskId(taskId);
    }

    @Test
    void cancelReminderNotifications_deletesUnsentByReminderId() {
        notificationService.cancelReminderNotifications(reminderId);

        verify(scheduledNotificationRepository).deleteUnsentByReminderId(reminderId);
    }

    @Test
    void scheduleReminder_stampsReminderIdOntoEveryScheduledRow() {
        // А2: без своей метки на строке снять одно напоминание было бы
        // нечем — задело бы либо всё по task_id, либо ничего.
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.of("user@example.com"));

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", fireAt, fireAt);

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ScheduledNotificationJpaEntity::getReminderId)
                .containsOnly(reminderId);
    }

    @Test
    void transferOwnership_passesTargetsIdentitiesForBothChannels() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(userService.findExternalId(to, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("999"));
        when(userService.findExternalId(to, IdentityProvider.EMAIL)).thenReturn(Optional.empty());

        notificationService.transferOwnership(from, to);

        verify(scheduledNotificationRepository).reassignOwner(from, to, "999", null);
    }

    @Test
    void transferOwnership_passesNullForBothChannels_whenTargetHasNeitherIdentity() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(userService.findExternalId(to, IdentityProvider.TELEGRAM)).thenReturn(Optional.empty());
        when(userService.findExternalId(to, IdentityProvider.EMAIL)).thenReturn(Optional.empty());

        notificationService.transferOwnership(from, to);

        verify(scheduledNotificationRepository).reassignOwner(from, to, null, null);
    }

    @Test
    void scheduleReminder_skipsEverythingWhenMasterSwitchIsOff() {
        when(userService.getSettings(userId)).thenReturn(settings(false, true, true, true));

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", fireAt, fireAt);

        verify(scheduledNotificationRepository, never()).save(any());
        verify(userService, never()).findExternalId(any(), any());
    }

    @Test
    void scheduleReminder_doesNotCheckIdentityForDisabledChannel() {
        // Выключенный переключатель не должен даже смотреть на идентичность —
        // при включённом он бы её нашёл, поэтому это разница именно от тумблера.
        when(userService.getSettings(userId)).thenReturn(settings(true, false, true, true));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.of("user@example.com"));

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", fireAt, fireAt);

        verify(userService, never()).findExternalId(userId, IdentityProvider.TELEGRAM);
        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository).save(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void scheduleReminder_skipsChannelWithIdentityButToggleOff() {
        when(userService.getSettings(userId)).thenReturn(settings(true, false, false, false));

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", fireAt, fireAt);

        verify(scheduledNotificationRepository, never()).save(any());
    }

    @Test
    void scheduleReminder_pushToggleOff_doesNotEvenLookUpSubscriptions() {
        // Как и с телеграмом/почтой: выключенный переключатель не должен даже
        // смотреть на подписки — иначе при включённом он бы их нашёл.
        when(userService.getSettings(userId)).thenReturn(settings(true, false, false, false));

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", fireAt, fireAt);

        verify(scheduledNotificationRepository, never()).save(any());
        verify(pushSubscriptionRepository, never()).findByUserId(any());
    }

    @Test
    void scheduleReminder_createsOneRowPerPushSubscription() {
        // Один пользователь — рабочий компьютер и телефон: обе подписки должны
        // получить своё напоминание, отказ одной не мешает другой.
        UUID subscriptionA = UUID.randomUUID();
        UUID subscriptionB = UUID.randomUUID();
        when(userService.findExternalId(any(), any())).thenReturn(Optional.empty());
        when(pushSubscriptionRepository.findByUserId(userId)).thenReturn(List.of(
                subscription(subscriptionA), subscription(subscriptionB)));

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", fireAt, fireAt);

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ScheduledNotificationJpaEntity::getChannel)
                .containsExactly(NotificationChannel.WEB_PUSH, NotificationChannel.WEB_PUSH);
        assertThat(captor.getAllValues()).extracting(ScheduledNotificationJpaEntity::getDestination)
                .containsExactlyInAnyOrder(subscriptionA.toString(), subscriptionB.toString());
    }

    @Test
    void scheduleReminder_noPushSubscriptions_noWebPushRow() {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.empty());
        when(pushSubscriptionRepository.findByUserId(userId)).thenReturn(List.of());

        notificationService.scheduleReminder(userId, taskId, reminderId, "задача", fireAt, fireAt);

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository).save(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo(NotificationChannel.TELEGRAM);
    }

    private PushSubscriptionJpaEntity subscription(UUID id) {
        var entity = new PushSubscriptionJpaEntity();
        entity.setId(id);
        return entity;
    }

    private UserSettingsDto defaultSettings() {
        return settings(true, true, true, true);
    }

    private UserSettingsDto settings(boolean notificationsEnabled, boolean notifyTelegram, boolean notifyEmail,
                                      boolean notifyPush) {
        return new UserSettingsDto(notificationsEnabled, notifyTelegram, notifyEmail, notifyPush,
                60, true, "groq", null, "SILENCE", "SILENCE", "Europe/Moscow", "user");
    }
}
