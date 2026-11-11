package ru.taskflow.user.api;

import ru.taskflow.user.api.dto.IdentityDto;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;
import ru.taskflow.user.api.dto.UserSettingsDto;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserService {

    UserDto findOrCreateByIdentity(IdentityProvider provider, String externalId, UserProfile profile);

    UserDto findOrCreateByTelegram(long telegramId, String username, String firstName, String lastName);

    Optional<String> findExternalId(UUID userId, IdentityProvider provider);

    UserDto findById(UUID userId);

    UserSettingsDto getSettings(UUID userId);

    void recordActivity(UUID userId);

    void updateSettings(UUID userId, UpdateSettingsRequest request);

    ZoneId getTimezone(UUID userId);

    List<IdentityDto> listIdentities(UUID userId);

    /**
     * Привязывает идентификатор к учётке userId. Если идентификатор уже
     * принадлежит другой учётке — бросает IdentityConflictException, а не
     * перевешивает его молча.
     */
    IdentityDto bindIdentity(UUID userId, IdentityProvider provider, String externalId);

    /**
     * Отвязывает способ входа. Бросает ValidationException, если это
     * последний способ у учётки.
     */
    void unbindIdentity(UUID userId, IdentityProvider provider);

    /**
     * Чья это идентичность прямо сейчас, без попытки привязать. Для переноса
     * данных: нужно узнать владельца заново, а не полагаться на конфликт,
     * полученный в предыдущем запросе — он мог протухнуть.
     */
    Optional<UUID> findIdentityOwner(IdentityProvider provider, String externalId);

    /**
     * Переносит все идентичности учётки from на to — часть переноса данных
     * при объединении учёток. После этого from остаётся без единого способа
     * входа: это ожидаемо, реального удаления учётки здесь не происходит.
     */
    int transferIdentities(UUID from, UUID to);
}