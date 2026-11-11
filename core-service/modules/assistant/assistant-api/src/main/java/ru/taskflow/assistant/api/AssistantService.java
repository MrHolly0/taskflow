package ru.taskflow.assistant.api;

import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AssistantService {

    // Telegram не различает диалог и быстрое добавление — там всегда CHAT,
    // отсюда default-перегрузки без AssistantEntryPoint.
    default Proposal handleText(UUID userId, String text, AssistantChannel channel) {
        return handleText(userId, text, channel, AssistantEntryPoint.CHAT);
    }

    Proposal handleText(UUID userId, String text, AssistantChannel channel, AssistantEntryPoint entryPoint);

    Proposal handleText(UUID userId, String text, AssistantChannel channel, AssistantEntryPoint entryPoint, String requestKey);

    default Proposal handleVoice(UUID userId, byte[] audio, AssistantChannel channel) {
        return handleVoice(userId, audio, channel, AssistantEntryPoint.CHAT);
    }

    Proposal handleVoice(UUID userId, byte[] audio, AssistantChannel channel, AssistantEntryPoint entryPoint);

    Proposal handleVoice(UUID userId, byte[] audio, AssistantChannel channel, AssistantEntryPoint entryPoint, String requestKey);

    Proposal handleVoiceUpload(UUID userId, java.util.function.Supplier<byte[]> audio,
                               AssistantChannel channel, AssistantEntryPoint entryPoint, String requestKey);

    ru.taskflow.assistant.api.dto.AssistantResult resolveAutomatically(UUID userId, UUID proposalId);
    ApplyResult result(UUID userId,UUID proposalId);

    Proposal findById(UUID userId, UUID proposalId);

    Proposal findByShortCode(UUID userId, String shortCode);

    /**
     * Последнее незакрытое предложение пользователя (status = PENDING, срок не
     * истёк), если оно есть — для быстрого пути подтверждения без обращения к
     * модели: FastPathResolver должен знать, есть ли контекст для «да»/«нет».
     */
    Optional<Proposal> findLatestPending(UUID userId);

    Proposal setActionAccepted(UUID userId, UUID proposalId, int ordinal, boolean accepted);

    /**
     * Правка времени напоминания у отдельного действия предложения (Г2) — не
     * требует захода в карточку задачи: reminderAt=null снимает напоминание
     * (равносильно осознанному отказу от него), непрошедшее время заменяет
     * прежнее. Применимо только к create и remind — у остальных типов
     * действий напоминание не имеет смысла.
     */
    Proposal updateActionReminder(UUID userId, UUID proposalId, int ordinal, OffsetDateTime reminderAt);
    Proposal updateActionReminders(UUID userId, UUID proposalId, int ordinal, java.util.List<OffsetDateTime> reminderAts);

    /**
     * Правка дня исполнения у отдельного create-действия предложения (В2) —
     * та же прямая пара к updateActionReminder, что и planned_date к
     * reminder_at в контракте: plannedDate=null снимает предложенный день
     * (равносильно осознанному отказу от него).
     */
    Proposal updateActionPlannedDate(UUID userId, UUID proposalId, int ordinal, OffsetDateTime plannedDate);

    /**
     * Выбор одного варианта среди взаимоисключающих альтернатив: принимает
     * указанный ordinal, отклоняет все остальные действия предложения одним
     * атомарным вызовом — без гонки между двумя последовательными PATCH.
     */
    Proposal selectAlternative(UUID userId, UUID proposalId, int ordinal);

    ApplyResult apply(UUID userId, UUID proposalId);

    ApplyResult apply(UUID userId, UUID proposalId, java.util.List<ru.taskflow.assistant.api.dto.ProposedAction> expectedActions);

    void reject(UUID userId, UUID proposalId);

    int transferOwnership(UUID from, UUID to);
}
