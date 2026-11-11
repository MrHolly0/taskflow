package ru.taskflow.user.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.shared.exception.IdentityConflictException;
import ru.taskflow.shared.exception.NotFoundException;
import ru.taskflow.shared.exception.ValidationException;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserProfile;
import ru.taskflow.user.api.dto.IdentityDto;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;
import ru.taskflow.user.api.dto.UserSettingsDto;
import ru.taskflow.user.infrastructure.persistence.UserIdentityJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserIdentityRepository;
import ru.taskflow.user.infrastructure.persistence.UserJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserRepository;
import ru.taskflow.user.infrastructure.persistence.UserSettingsJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserSettingsRepository;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSettingsRepository settingsRepository;

    @Mock
    private UserIdentityRepository identityRepository;

    @Test
    void getTimezone_returnsStoredZone() {
        UUID userId = UUID.randomUUID();
        UserJpaEntity entity = new UserJpaEntity();
        entity.setTimezone("Asia/Yekaterinburg");
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));
        UserServiceImpl service = newService();

        ZoneId zone = service.getTimezone(userId);

        assertThat(zone).isEqualTo(ZoneId.of("Asia/Yekaterinburg"));
    }

    @Test
    void getTimezone_throwsWhenUserMissing() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        UserServiceImpl service = newService();

        assertThatThrownBy(() -> service.getTimezone(userId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getSettings_defaultsVoiceModesToSilenceWhenNoneStored() {
        UUID userId = UUID.randomUUID();
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        UserServiceImpl service = newService();

        UserSettingsDto settings = service.getSettings(userId);

        assertThat(settings.voiceInputModeDesktop()).isEqualTo("SILENCE");
        assertThat(settings.voiceInputModeMobile()).isEqualTo("SILENCE");
    }

    @Test
    void updateSettings_updatesVoiceModes() {
        UUID userId = UUID.randomUUID();
        var entity = new UserSettingsJpaEntity();
        var request = new UpdateSettingsRequest(null, null, null, null, null, null, null, null, "TOGGLE", "HOLD", null, null);
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));
        UserServiceImpl service = newService();

        service.updateSettings(userId, request);

        ArgumentCaptor<UserSettingsJpaEntity> captor = ArgumentCaptor.forClass(UserSettingsJpaEntity.class);
        verify(settingsRepository).save(captor.capture());
        assertThat(captor.getValue().getVoiceInputModeDesktop()).isEqualTo("TOGGLE");
        assertThat(captor.getValue().getVoiceInputModeMobile()).isEqualTo("HOLD");
    }

    @Test
    void updateSettings_updatesNotifyPush() {
        UUID userId = UUID.randomUUID();
        var entity = new UserSettingsJpaEntity();
        var request = new UpdateSettingsRequest(null, null, null, false, null, null, null, null, null, null, null, null);
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));
        UserServiceImpl service = newService();

        service.updateSettings(userId, request);

        ArgumentCaptor<UserSettingsJpaEntity> captor = ArgumentCaptor.forClass(UserSettingsJpaEntity.class);
        verify(settingsRepository).save(captor.capture());
        assertThat(captor.getValue().isNotifyPush()).isFalse();
    }

    @Test
    void getSettings_noRowYet_defaultsEmailOffTelegramOnPushOff() {
        // 20.08.2026: бесплатные каналы включены по умолчанию, личный ящик —
        // нет. Это должно быть видно уже в самом первом ответе /settings, до
        // того как для пользователя вообще создана строка user_settings.
        // push — отдельно: "включён" может означать только "разрешение
        // браузера выдано и подписка жива", а у свежей учётки нет ни того,
        // ни другого — включённым по умолчанию оно быть не может.
        UUID userId = UUID.randomUUID();
        var user = new UserJpaEntity();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        UserServiceImpl service = newService();

        UserSettingsDto settings = service.getSettings(userId);

        assertThat(settings.notifyEmail()).isFalse();
        assertThat(settings.notifyTelegram()).isTrue();
        assertThat(settings.notifyPush()).isFalse();
    }

    @Test
    void getSettings_returnsStoredTimezone() {
        UUID userId = UUID.randomUUID();
        var user = new UserJpaEntity();
        user.setTimezone("Asia/Yekaterinburg");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        UserServiceImpl service = newService();

        UserSettingsDto settings = service.getSettings(userId);

        assertThat(settings.timezone()).isEqualTo("Asia/Yekaterinburg");
    }

    @Test
    void updateSettings_savesValidTimezone() {
        UUID userId = UUID.randomUUID();
        var settingsEntity = new UserSettingsJpaEntity();
        var user = new UserJpaEntity();
        var request = new UpdateSettingsRequest(null, null, null, null, null, null, null, null, null, null, "Asia/Yekaterinburg", null);
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.of(settingsEntity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        UserServiceImpl service = newService();

        service.updateSettings(userId, request);

        ArgumentCaptor<UserJpaEntity> captor = ArgumentCaptor.forClass(UserJpaEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getTimezone()).isEqualTo("Asia/Yekaterinburg");
    }

    @Test
    void updateSettings_rejectsUnknownTimezone() {
        UUID userId = UUID.randomUUID();
        var request = new UpdateSettingsRequest(null, null, null, null, null, null, null, null, null, null, "Mars/Colony", null);
        UserServiceImpl service = newService();

        assertThatThrownBy(() -> service.updateSettings(userId, request))
                .isInstanceOf(ValidationException.class);

        verify(userRepository, never()).save(any());
        verify(settingsRepository, never()).save(any());
    }

    @Test
    void getSettings_returnsStoredDisplayName() {
        UUID userId = UUID.randomUUID();
        var user = new UserJpaEntity();
        user.setUsername("marina");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        UserServiceImpl service = newService();

        UserSettingsDto settings = service.getSettings(userId);

        assertThat(settings.displayName()).isEqualTo("marina");
    }

    @Test
    void updateSettings_savesValidDisplayName() {
        UUID userId = UUID.randomUUID();
        var settingsEntity = new UserSettingsJpaEntity();
        var user = new UserJpaEntity();
        var request = new UpdateSettingsRequest(null, null, null, null, null, null, null, null, null, null, null, "Марина");
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.of(settingsEntity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        UserServiceImpl service = newService();

        service.updateSettings(userId, request);

        ArgumentCaptor<UserJpaEntity> captor = ArgumentCaptor.forClass(UserJpaEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("Марина");
    }

    @Test
    void updateSettings_rejectsBlankDisplayName() {
        UUID userId = UUID.randomUUID();
        var request = new UpdateSettingsRequest(null, null, null, null, null, null, null, null, null, null, null, "   ");
        UserServiceImpl service = newService();

        assertThatThrownBy(() -> service.updateSettings(userId, request))
                .isInstanceOf(ValidationException.class);

        verify(userRepository, never()).save(any());
        verify(settingsRepository, never()).save(any());
    }

    @Test
    void updateSettings_rejectsTooLongDisplayName() {
        UUID userId = UUID.randomUUID();
        var request = new UpdateSettingsRequest(null, null, null, null, null, null, null, null, null, null, null, "a".repeat(65));
        UserServiceImpl service = newService();

        assertThatThrownBy(() -> service.updateSettings(userId, request))
                .isInstanceOf(ValidationException.class);

        verify(userRepository, never()).save(any());
        verify(settingsRepository, never()).save(any());
    }

    @Test
    void updateSettings_leavesDisplayNameUntouchedWhenNotProvided() {
        UUID userId = UUID.randomUUID();
        var settingsEntity = new UserSettingsJpaEntity();
        var request = new UpdateSettingsRequest(null, null, null, null, null, null, null, null, "TOGGLE", null, null, null);
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.of(settingsEntity));
        UserServiceImpl service = newService();

        service.updateSettings(userId, request);

        verify(userRepository, never()).save(any());
    }

    @Test
    void findOrCreateByIdentity_findsExistingUserByIdentity() {
        UUID userId = UUID.randomUUID();
        var user = new UserJpaEntity();
        user.setId(userId);
        user.setUsername("someone");
        var identity = new UserIdentityJpaEntity();
        identity.setUser(user);
        identity.setProvider(IdentityProvider.TELEGRAM);
        identity.setExternalId("42");
        when(identityRepository.findByProviderAndExternalId(IdentityProvider.TELEGRAM, "42"))
                .thenReturn(Optional.of(identity));
        UserServiceImpl service = newService();

        var dto = service.findOrCreateByIdentity(IdentityProvider.TELEGRAM, "42",
                new UserProfile("someone", "First", "Last", null));

        assertThat(dto.id()).isEqualTo(userId);
        verify(userRepository, never()).save(any());
    }

    @Test
    void findOrCreateByIdentity_createsUserAndIdentityWhenMissing() {
        when(identityRepository.findByProviderAndExternalId(IdentityProvider.TELEGRAM, "42"))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(UserJpaEntity.class))).thenAnswer(inv -> {
            UserJpaEntity saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        UserServiceImpl service = newService();

        var dto = service.findOrCreateByIdentity(IdentityProvider.TELEGRAM, "42",
                new UserProfile("someone", "First", "Last", null));

        assertThat(dto.id()).isNotNull();
        ArgumentCaptor<UserIdentityJpaEntity> captor = ArgumentCaptor.forClass(UserIdentityJpaEntity.class);
        verify(identityRepository).save(captor.capture());
        assertThat(captor.getValue().getProvider()).isEqualTo(IdentityProvider.TELEGRAM);
        assertThat(captor.getValue().getExternalId()).isEqualTo("42");
    }

    @Test
    void findOrCreateByTelegram_delegatesToFindOrCreateByIdentity() {
        when(identityRepository.findByProviderAndExternalId(IdentityProvider.TELEGRAM, "42"))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(UserJpaEntity.class))).thenAnswer(inv -> {
            UserJpaEntity saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        UserServiceImpl service = newService();

        service.findOrCreateByTelegram(42L, "someone", "First", "Last");

        verify(identityRepository).findByProviderAndExternalId(IdentityProvider.TELEGRAM, "42");
    }

    @Test
    void findExternalId_returnsExternalIdWhenIdentityExists() {
        UUID userId = UUID.randomUUID();
        var identity = new UserIdentityJpaEntity();
        identity.setExternalId("12345");
        when(identityRepository.findByUser_IdAndProvider(userId, IdentityProvider.TELEGRAM))
                .thenReturn(Optional.of(identity));
        UserServiceImpl service = newService();

        var externalId = service.findExternalId(userId, IdentityProvider.TELEGRAM);

        assertThat(externalId).contains("12345");
    }

    @Test
    void findExternalId_returnsEmptyWhenIdentityMissing() {
        UUID userId = UUID.randomUUID();
        when(identityRepository.findByUser_IdAndProvider(userId, IdentityProvider.TELEGRAM))
                .thenReturn(Optional.empty());
        UserServiceImpl service = newService();

        var externalId = service.findExternalId(userId, IdentityProvider.TELEGRAM);

        assertThat(externalId).isEmpty();
    }

    @Test
    void listIdentities_mapsAllIdentitiesOfUser() {
        UUID userId = UUID.randomUUID();
        var telegram = new UserIdentityJpaEntity();
        telegram.setProvider(IdentityProvider.TELEGRAM);
        telegram.setExternalId("42");
        var email = new UserIdentityJpaEntity();
        email.setProvider(IdentityProvider.EMAIL);
        email.setExternalId("a@b.com");
        when(identityRepository.findByUser_Id(userId)).thenReturn(List.of(telegram, email));
        UserServiceImpl service = newService();

        var result = service.listIdentities(userId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(IdentityDto::provider)
                .containsExactlyInAnyOrder(IdentityProvider.TELEGRAM, IdentityProvider.EMAIL);
    }

    @Test
    void bindIdentity_createsNewIdentity_whenExternalIdFree() {
        UUID userId = UUID.randomUUID();
        var user = new UserJpaEntity();
        user.setId(userId);
        when(identityRepository.findByProviderAndExternalId(IdentityProvider.EMAIL, "a@b.com"))
                .thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(identityRepository.save(any(UserIdentityJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        UserServiceImpl service = newService();

        var result = service.bindIdentity(userId, IdentityProvider.EMAIL, "a@b.com");

        assertThat(result.provider()).isEqualTo(IdentityProvider.EMAIL);
        assertThat(result.externalId()).isEqualTo("a@b.com");
        ArgumentCaptor<UserIdentityJpaEntity> captor = ArgumentCaptor.forClass(UserIdentityJpaEntity.class);
        verify(identityRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(user);
    }

    @Test
    void bindIdentity_isIdempotent_whenAlreadyBoundToSameUser() {
        UUID userId = UUID.randomUUID();
        var user = new UserJpaEntity();
        user.setId(userId);
        var existing = new UserIdentityJpaEntity();
        existing.setUser(user);
        existing.setProvider(IdentityProvider.EMAIL);
        existing.setExternalId("a@b.com");
        when(identityRepository.findByProviderAndExternalId(IdentityProvider.EMAIL, "a@b.com"))
                .thenReturn(Optional.of(existing));
        UserServiceImpl service = newService();

        var result = service.bindIdentity(userId, IdentityProvider.EMAIL, "a@b.com");

        assertThat(result.externalId()).isEqualTo("a@b.com");
        verify(identityRepository, never()).save(any());
    }

    @Test
    void bindIdentity_throwsConflict_whenBoundToDifferentUser() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        var otherUser = new UserJpaEntity();
        otherUser.setId(otherUserId);
        var existing = new UserIdentityJpaEntity();
        existing.setUser(otherUser);
        existing.setProvider(IdentityProvider.EMAIL);
        existing.setExternalId("a@b.com");
        when(identityRepository.findByProviderAndExternalId(IdentityProvider.EMAIL, "a@b.com"))
                .thenReturn(Optional.of(existing));
        UserServiceImpl service = newService();

        assertThatThrownBy(() -> service.bindIdentity(userId, IdentityProvider.EMAIL, "a@b.com"))
                .isInstanceOf(IdentityConflictException.class)
                .satisfies(e -> assertThat(((IdentityConflictException) e).getConflictingUserId()).isEqualTo(otherUserId));
    }

    @Test
    void unbindIdentity_deletesIdentity_whenMoreThanOneRemains() {
        UUID userId = UUID.randomUUID();
        var identity = new UserIdentityJpaEntity();
        identity.setProvider(IdentityProvider.TELEGRAM);
        when(identityRepository.findByUser_IdAndProvider(userId, IdentityProvider.TELEGRAM))
                .thenReturn(Optional.of(identity));
        when(identityRepository.countByUser_Id(userId)).thenReturn(2L);
        UserServiceImpl service = newService();

        service.unbindIdentity(userId, IdentityProvider.TELEGRAM);

        verify(identityRepository).delete(identity);
    }

    @Test
    void unbindIdentity_throwsValidation_whenLastRemaining() {
        UUID userId = UUID.randomUUID();
        var identity = new UserIdentityJpaEntity();
        identity.setProvider(IdentityProvider.TELEGRAM);
        when(identityRepository.findByUser_IdAndProvider(userId, IdentityProvider.TELEGRAM))
                .thenReturn(Optional.of(identity));
        when(identityRepository.countByUser_Id(userId)).thenReturn(1L);
        UserServiceImpl service = newService();

        assertThatThrownBy(() -> service.unbindIdentity(userId, IdentityProvider.TELEGRAM))
                .isInstanceOf(ValidationException.class);
        verify(identityRepository, never()).delete(any());
    }

    @Test
    void unbindIdentity_throwsNotFound_whenNotBound() {
        UUID userId = UUID.randomUUID();
        when(identityRepository.findByUser_IdAndProvider(userId, IdentityProvider.TELEGRAM))
                .thenReturn(Optional.empty());
        UserServiceImpl service = newService();

        assertThatThrownBy(() -> service.unbindIdentity(userId, IdentityProvider.TELEGRAM))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void findIdentityOwner_returnsOwnerUserId() {
        UUID ownerId = UUID.randomUUID();
        var owner = new UserJpaEntity();
        owner.setId(ownerId);
        var identity = new UserIdentityJpaEntity();
        identity.setUser(owner);
        when(identityRepository.findByProviderAndExternalId(IdentityProvider.EMAIL, "a@b.com"))
                .thenReturn(Optional.of(identity));
        UserServiceImpl service = newService();

        var result = service.findIdentityOwner(IdentityProvider.EMAIL, "a@b.com");

        assertThat(result).contains(ownerId);
    }

    @Test
    void findIdentityOwner_returnsEmpty_whenNobodyOwnsIt() {
        when(identityRepository.findByProviderAndExternalId(IdentityProvider.EMAIL, "a@b.com"))
                .thenReturn(Optional.empty());
        UserServiceImpl service = newService();

        var result = service.findIdentityOwner(IdentityProvider.EMAIL, "a@b.com");

        assertThat(result).isEmpty();
    }

    @Test
    void transferIdentities_delegatesToRepositoryWithEntityReferences() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        var fromRef = new UserJpaEntity();
        fromRef.setId(fromId);
        var toRef = new UserJpaEntity();
        toRef.setId(toId);
        when(userRepository.getReferenceById(fromId)).thenReturn(fromRef);
        when(userRepository.getReferenceById(toId)).thenReturn(toRef);
        when(identityRepository.reassignOwner(fromRef, toRef)).thenReturn(2);
        UserServiceImpl service = newService();

        int result = service.transferIdentities(fromId, toId);

        assertThat(result).isEqualTo(2);
    }

    private UserServiceImpl newService() {
        return new UserServiceImpl(event -> {}, userRepository, settingsRepository, identityRepository);
    }
}
