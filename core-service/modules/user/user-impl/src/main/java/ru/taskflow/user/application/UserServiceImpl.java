package ru.taskflow.user.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.shared.exception.NotFoundException;
import ru.taskflow.user.api.UserDto;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.infrastructure.persistence.UserJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserRepository;

import java.util.UUID;

/**
 * Сервис управления учётными записями пользователей.
 *
 * Обеспечивает регистрацию и поиск пользователей, интегрируется с Telegram
 * для создания аккаунтов через инициализационные данные из Mini App.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    /**
     * Находит или создаёт пользователя по Telegram ID.
     *
     * Используется при авторизации через Telegram Mini App. Если пользователь
     * не существует, создаётся новый аккаунт с данными из профиля Telegram.
     *
     * @param telegramId ID пользователя в Telegram
     * @param username никнейм Telegram
     * @param firstName имя из профиля
     * @param lastName фамилия из профиля
     * @return DTO пользователя (новый или существующий)
     */
    @Override
    @Transactional
    public UserDto findOrCreateByTelegram(long telegramId, String username, String firstName, String lastName) {
        return userRepository.findByTelegramId(telegramId)
                .map(this::toDto)
                .orElseGet(() -> {
                    var entity = new UserJpaEntity();
                    entity.setTelegramId(telegramId);
                    entity.setUsername(username);
                    entity.setFirstName(firstName);
                    entity.setLastName(lastName);
                    return toDto(userRepository.save(entity));
                });
    }

    /**
     * Получает данные пользователя по ID.
     *
     * @param userId ID пользователя
     * @return DTO пользователя
     * @throws NotFoundException если пользователь не найден
     */
    @Override
    @Transactional(readOnly = true)
    public UserDto findById(UUID userId) {
        return userRepository.findById(userId)
                .map(this::toDto)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    private UserDto toDto(UserJpaEntity e) {
        return new UserDto(e.getId(), e.getTelegramId(), e.getUsername(), e.getFirstName(), e.getLastName());
    }
}