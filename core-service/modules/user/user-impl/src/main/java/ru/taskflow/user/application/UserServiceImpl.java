package ru.taskflow.user.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.shared.exception.IdentityConflictException;
import ru.taskflow.shared.exception.NotFoundException;
import ru.taskflow.shared.exception.ValidationException;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserDto;
import ru.taskflow.user.api.UserProfile;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.IdentityDto;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;
import ru.taskflow.user.api.dto.UserSettingsDto;
import ru.taskflow.user.infrastructure.persistence.UserIdentityJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserIdentityRepository;
import ru.taskflow.user.infrastructure.persistence.UserJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserRepository;
import ru.taskflow.user.infrastructure.persistence.UserSettingsJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserSettingsRepository;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_TIMEZONE = "Europe/Moscow";

    private final UserRepository userRepository;
    private final UserSettingsRepository settingsRepository;
    private final UserIdentityRepository identityRepository;

    @Override
    @Transactional
    public UserDto findOrCreateByIdentity(IdentityProvider provider, String externalId, UserProfile profile) {
        return identityRepository.findByProviderAndExternalId(provider, externalId)
                .map(identity -> toDto(identity.getUser()))
                .orElseGet(() -> {
                    var user = new UserJpaEntity();
                    user.setUsername(profile.username());
                    user.setFirstName(profile.firstName());
                    user.setLastName(profile.lastName());
                    if (profile.languageCode() != null) {
                        user.setLanguageCode(profile.languageCode());
                    }
                    var savedUser = userRepository.save(user);

                    var identity = new UserIdentityJpaEntity();
                    identity.setUser(savedUser);
                    identity.setProvider(provider);
                    identity.setExternalId(externalId);
                    identity.setVerifiedAt(OffsetDateTime.now());
                    identityRepository.save(identity);
                    var defaults=new UserSettingsJpaEntity();defaults.setUser(savedUser);
                    settingsRepository.save(defaults);
                    return toDto(savedUser);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findExternalId(UUID userId, IdentityProvider provider) {
        return identityRepository.findByUser_IdAndProvider(userId, provider)
                .map(UserIdentityJpaEntity::getExternalId);
    }

    @Override
    @Transactional
    public UserDto findOrCreateByTelegram(long telegramId, String username, String firstName, String lastName) {
        return findOrCreateByIdentity(IdentityProvider.TELEGRAM, Long.toString(telegramId),
                new UserProfile(username, firstName, lastName, null));
    }

    @Override
    @Transactional(readOnly = true)
    public UserDto findById(UUID userId) {
        return userRepository.findById(userId)
                .map(this::toDto)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    @Override
    @Transactional(readOnly = true)
    public UserSettingsDto getSettings(UUID userId) {
        var user = userRepository.findById(userId);
        String timezone = user.map(UserJpaEntity::getTimezone).orElse(DEFAULT_TIMEZONE);
        String displayName = user.map(UserJpaEntity::getUsername).orElse(null);
        return settingsRepository.findByUserId(userId)
                .map(e -> toSettingsDto(e, timezone, displayName))
                .orElseGet(() -> defaultSettings(timezone, displayName));
    }

    @Override
    @Transactional
    public void recordActivity(UUID userId) {
        userRepository.recordActivity(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public ZoneId getTimezone(UUID userId) {
        return userRepository.findById(userId)
                .map(e -> ZoneId.of(e.getTimezone()))
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    @Override
    @Transactional
    public void updateSettings(UUID userId, UpdateSettingsRequest request) {
        if (request.timezone() != null) {
            validateTimezone(request.timezone());
        }
        if (request.displayName() != null) {
            validateDisplayName(request.displayName());
        }

        var settings = settingsRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSettings(userId));

        if (request.notificationsEnabled() != null) settings.setNotificationsEnabled(request.notificationsEnabled());
        if (request.notifyTelegram() != null) settings.setNotifyTelegram(request.notifyTelegram());
        if (request.notifyEmail() != null) settings.setNotifyEmail(request.notifyEmail());
        if (request.notifyPush() != null) settings.setNotifyPush(request.notifyPush());
        if (request.defaultReminderMinutes() != null) settings.setDefaultReminderMinutes(request.defaultReminderMinutes());
        if (request.urgentExtraReminder() != null) settings.setUrgentExtraReminder(request.urgentExtraReminder());
        if (request.preferredLlm() != null) settings.setPreferredLlm(request.preferredLlm());
        if (request.autoCleanCompletedDays() != null) settings.setAutoCleanCompletedDays(request.autoCleanCompletedDays());
        if (request.voiceInputModeDesktop() != null) settings.setVoiceInputModeDesktop(request.voiceInputModeDesktop());
        if (request.voiceInputModeMobile() != null) settings.setVoiceInputModeMobile(request.voiceInputModeMobile());

        if (request.taskCheckinsEnabled() != null) {
            if (request.taskCheckinsEnabled() && !settings.isTaskCheckinsEnabled()) settings.setTaskCheckinsEnabledAt(OffsetDateTime.now());
            settings.setTaskCheckinsEnabled(request.taskCheckinsEnabled());
        }
        if (request.returnNudgesEnabled() != null) {
            if (request.returnNudgesEnabled() && !settings.isReturnNudgesEnabled()) settings.setReturnNudgesEnabledAt(OffsetDateTime.now());
            settings.setReturnNudgesEnabled(request.returnNudgesEnabled());
        }
        if (request.telegramWidgetsEnabled() != null) settings.setTelegramWidgetsEnabled(request.telegramWidgetsEnabled());
        settingsRepository.save(settings);

        if (request.timezone() != null || request.displayName() != null) {
            var user = userRepository.findById(userId)
                    .orElseThrow(() -> new NotFoundException("User not found: " + userId));
            if (request.timezone() != null) {
                user.setTimezone(request.timezone());
            }
            if (request.displayName() != null) {
                user.setUsername(request.displayName());
            }
            userRepository.save(user);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdentityDto> listIdentities(UUID userId) {
        return identityRepository.findByUser_Id(userId).stream()
                .map(this::toIdentityDto)
                .toList();
    }

    @Override
    @Transactional
    public IdentityDto bindIdentity(UUID userId, IdentityProvider provider, String externalId) {
        var existing = identityRepository.findByProviderAndExternalId(provider, externalId);
        if (existing.isPresent()) {
            var identity = existing.get();
            if (!identity.getUserId().equals(userId)) {
                throw new IdentityConflictException(
                        "Этот способ входа уже привязан к другой учётке", identity.getUserId());
            }
            return toIdentityDto(identity);
        }

        var user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        var identity = new UserIdentityJpaEntity();
        identity.setUser(user);
        identity.setProvider(provider);
        identity.setExternalId(externalId);
        identity.setVerifiedAt(OffsetDateTime.now());
        return toIdentityDto(identityRepository.save(identity));
    }

    @Override
    @Transactional
    public void unbindIdentity(UUID userId, IdentityProvider provider) {
        var identity = identityRepository.findByUser_IdAndProvider(userId, provider)
                .orElseThrow(() -> new NotFoundException("Способ входа не привязан к этой учётке"));

        if (identityRepository.countByUser_Id(userId) <= 1) {
            throw new ValidationException("Нельзя отвязать последний способ входа");
        }

        identityRepository.delete(identity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findIdentityOwner(IdentityProvider provider, String externalId) {
        return identityRepository.findByProviderAndExternalId(provider, externalId)
                .map(UserIdentityJpaEntity::getUserId);
    }

    @Override
    @Transactional
    public int transferIdentities(UUID from, UUID to) {
        var fromUser = userRepository.getReferenceById(from);
        var toUser = userRepository.getReferenceById(to);
        return identityRepository.reassignOwner(fromUser, toUser);
    }

    private IdentityDto toIdentityDto(UserIdentityJpaEntity e) {
        return new IdentityDto(e.getProvider(), e.getExternalId(), e.getVerifiedAt());
    }

    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new ValidationException("Неизвестный часовой пояс: " + timezone);
        }
    }

    private void validateDisplayName(String displayName) {
        if (displayName.isBlank()) {
            throw new ValidationException("Имя не может быть пустым");
        }
        if (displayName.length() > 64) {
            throw new ValidationException("Имя слишком длинное (максимум 64 символа)");
        }
    }

    private UserSettingsJpaEntity createDefaultSettings(UUID userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        var settings = new UserSettingsJpaEntity();
        settings.setUser(user);
        return settings;
    }

    private UserSettingsDto toSettingsDto(UserSettingsJpaEntity e, String timezone, String displayName) {
        return new UserSettingsDto(
                e.isNotificationsEnabled(),
                e.isNotifyTelegram(),
                e.isNotifyEmail(),
                e.isNotifyPush(),
                e.getDefaultReminderMinutes(),
                e.isUrgentExtraReminder(),
                e.getPreferredLlm(),
                e.getAutoCleanCompletedDays(),
                e.getVoiceInputModeDesktop(),
                e.getVoiceInputModeMobile(),
                timezone,
                displayName,
                e.isTaskCheckinsEnabled(),
                e.isReturnNudgesEnabled(),
                e.isTelegramWidgetsEnabled()
        );
    }

    private UserSettingsDto defaultSettings(String timezone, String displayName) {
        // notifyPush=false — "включён" без разрешения браузера и живой
        // подписки не бывает, а у свежей учётки их ещё нет ни одного.
        return new UserSettingsDto(true, true, false, false, 60, true, "groq", null, "SILENCE", "SILENCE", timezone, displayName,true,true);
    }

    private UserDto toDto(UserJpaEntity e) {
        return new UserDto(e.getId(), e.getUsername(), e.getFirstName(), e.getLastName());
    }
}
