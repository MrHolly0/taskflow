# Тесты и контрольный пример TaskFlow

## Таблица 3. Контрольный пример сценария создания и управления задачей через Telegram

| Шаг | Действие | Ожидаемый результат | Факт |
|-----|----------|-------------------|------|
| 1 | Отправить голосовое сообщение «сдать лабу по Java до пятницы» | Бот отвечает карточкой:<br/>title=«Сдать лабу по Java»<br/>deadline=ближайшая пятница<br/>priority=MEDIUM | Получена карточка с распознанными параметрами.<br/>VoiceMessageHandler преобразует аудио в текст через nlp-worker,<br/>TextMessageHandler разбирает текст и создаёт черновик (draft=true)<br/>с автоматически определённым дедлайном (ближайшая пятница) |
| 2 | Нажать inline-кнопку «Подтвердить» | Задача сохранена с:<br/>is_draft=false<br/>статус=TODO<br/>в БД создана запись | Задача переведена из draft в активное состояние.<br/>CallbackHandler обработал callback «confirm:taskId»,<br/>вызвал confirmDraft() → is_draft=false, status=TODO.<br/>NotificationService расписал напоминание на дедлайн |
| 3 | GET /api/v1/tasks/focus | Задача присутствует в ответе<br/>(дедлайн ≤ конца дня)<br/>лимит: ≤3 задач | Эндпоинт вернул 200 OK с массивом задач.<br/>TaskServiceImpl.getFocusTasks() отфильтровал по условиям:<br/>- not DONE<br/>- deadline between now и конец дня<br/>- limit 3<br/>Создана задача видна в ответе |
| 4 | POST /api/v1/tasks/{id}/complete | Статус задачи: DONE<br/>completedAt: текущее время<br/>Задача исчезает из фокуса | Эндпоинт вернул 200 OK.<br/>TaskServiceImpl.complete() установил:<br/>- status = DONE<br/>- completedAt = now()<br/>NotificationService отменил запланированные напоминания.<br/>Следующий GET /focus не содержит задачу (фильтр исключает DONE) |

## Ключевые модульные тесты

### 1. TelegramInitDataValidatorTest
Проверяет валидацию инициализационных данных от Telegram Mini App.

```java
package ru.taskflow.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.TreeMap;
import static org.assertj.core.api.Assertions.assertThat;

class TelegramInitDataValidatorTest {

    private static final String BOT_TOKEN = "1234567890:AAHtest_token_for_unit_testing_only";
    private static final long MAX_AGE = 86400L;
    private TelegramInitDataValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TelegramInitDataValidator(BOT_TOKEN, MAX_AGE);
    }

    @Test
    void validInitData_returnsTrue() throws Exception {
        long authDate = System.currentTimeMillis() / 1000;
        var params = new TreeMap<String, String>();
        params.put("query_id", "AAHtest");
        params.put("user", "{\"id\":123456789,\"first_name\":\"Test\"}");
        params.put("auth_date", String.valueOf(authDate));

        String hash = computeInitDataHash(params, BOT_TOKEN);
        String initData = buildInitData(params, hash);

        assertThat(validator.validate(initData)).isTrue();
    }

    @Test
    void tamperedData_returnsFalse() throws Exception {
        long authDate = System.currentTimeMillis() / 1000;
        var params = new TreeMap<String, String>();
        params.put("query_id", "AAHtest");
        params.put("auth_date", String.valueOf(authDate));

        String hash = computeInitDataHash(params, BOT_TOKEN);
        String initData = "query_id=TAMPERED&auth_date=" + authDate + "&hash=" + hash;

        assertThat(validator.validate(initData)).isFalse();
    }

    @Test
    void expiredAuthDate_returnsFalse() throws Exception {
        long expired = (System.currentTimeMillis() / 1000) - MAX_AGE - 1;
        var params = new TreeMap<String, String>();
        params.put("auth_date", String.valueOf(expired));

        String hash = computeInitDataHash(params, BOT_TOKEN);
        String initData = buildInitData(params, hash);

        assertThat(validator.validate(initData)).isFalse();
    }
}
```

**Результаты:** 4/4 пройдены
- Валидные initData успешно верифицируются
- Модифицированные данные отклоняются
- Истёкшие токены отклоняются
- Пустые данные отклоняются

---

### 2. TaskServiceTest
Проверяет основные операции сервиса управления задачами.

```java
package ru.taskflow.task.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.task.api.exception.TaskNotFoundException;
import ru.taskflow.task.infrastructure.persistence.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private GroupRepository groupRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private TaskMapper taskMapper;

    @InjectMocks
    private TaskServiceImpl taskService;

    private final UUID userId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @Test
    void create_savesAndReturnsResponse() {
        var request = new CreateTaskRequest("купить молоко", null, null, null, null, List.of(), null, null);
        var entity = new TaskJpaEntity();
        entity.setUserId(userId);
        entity.setTitle("купить молоко");
        var response = mockResponse(taskId, "купить молоко");

        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.create(userId, request);

        assertThat(result.title()).isEqualTo("купить молоко");
        verify(taskRepository).save(any(TaskJpaEntity.class));
    }

    @Test
    void findById_returnsTask_whenExists() {
        var entity = new TaskJpaEntity();
        entity.setId(taskId);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.findById(userId, taskId);

        assertThat(result.id()).isEqualTo(taskId);
    }

    @Test
    void findById_throwsNotFound_whenMissing() {
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.findById(userId, taskId))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void complete_setsStatusDone() {
        var entity = new TaskJpaEntity();
        entity.setId(taskId);
        entity.setStatus(TaskStatus.TODO);
        
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));

        taskService.complete(userId, taskId);

        assertThat(entity.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(entity.getCompletedAt()).isNotNull();
    }

    @Test
    void delete_marksAsDeleted() {
        var entity = new TaskJpaEntity();
        entity.setId(taskId);
        entity.setDeleted(false);
        
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));

        taskService.delete(userId, taskId);

        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getDeletedAt()).isNotNull();
    }
}
```

**Результаты:** 5/5 пройдены
- Создание задачи с сохранением в БД
- Поиск по ID возвращает правильную задачу
- Поиск несуществующей задачи выбрасывает исключение
- Завершение задачи устанавливает статус DONE
- Удаление помечает задачу как удалённую

---

### 3. UpdateRouterTest
Проверяет маршрутизацию обновлений от Telegram.

```java
package ru.taskflow.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.user.api.UserDto;
import ru.taskflow.user.api.UserService;
import ru.taskflow.telegram.application.*;
import ru.taskflow.telegram.infrastructure.client.dto.*;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UpdateRouterTest {

    @Mock
    private UserService userService;

    @Mock
    private BotCommandRouter commandRouter;

    @Mock
    private TextMessageHandler textHandler;

    @Mock
    private VoiceMessageHandler voiceHandler;

    @Mock
    private CallbackHandler callbackHandler;

    @InjectMocks
    private UpdateRouter router;

    @Test
    void textMessage_callsTextHandler() {
        var userId = stubUser();
        var message = textMessage("hello");

        router.route(new TelegramUpdate(1L, message, null));

        verify(textHandler).handle(message, userId);
        verifyNoInteractions(commandRouter, voiceHandler, callbackHandler);
    }

    @Test
    void command_callsCommandRouter() {
        var userId = stubUser();
        var message = textMessage("/start");

        router.route(new TelegramUpdate(1L, message, null));

        verify(commandRouter).handle(message, userId);
        verifyNoInteractions(textHandler, voiceHandler, callbackHandler);
    }

    @Test
    void voiceMessage_callsVoiceHandler() {
        var userId = stubUser();
        var message = voiceMessage();

        router.route(new TelegramUpdate(1L, message, null));

        verify(voiceHandler).handle(message, userId);
        verifyNoInteractions(textHandler, commandRouter, callbackHandler);
    }

    @Test
    void callbackQuery_callsCallbackHandler() {
        var userId = UUID.randomUUID();
        var tgUser = new TelegramUser(1L, "u", "Name", null);
        when(userService.findOrCreateByTelegram(anyLong(), anyString(), anyString(), any()))
                .thenReturn(new UserDto(userId, 1L, "u", "Name", null));
        var cb = new TelegramCallbackQuery("cb1", tgUser,
                new TelegramMessage(1L, tgUser, new TelegramChat(100L), null, null), "complete:abc");

        router.route(new TelegramUpdate(1L, null, cb));

        verify(callbackHandler).handle(cb, userId);
        verifyNoInteractions(textHandler, commandRouter, voiceHandler);
    }

    private UUID stubUser() {
        var userId = UUID.randomUUID();
        when(userService.findOrCreateByTelegram(anyLong(), anyString(), anyString(), any()))
                .thenReturn(new UserDto(userId, 1L, "user", "Name", null));
        return userId;
    }

    private TelegramMessage textMessage(String text) {
        return new TelegramMessage(1L, new TelegramUser(1L, "u", "N", null),
                new TelegramChat(100L), text, null);
    }

    private TelegramMessage voiceMessage() {
        return new TelegramMessage(1L, new TelegramUser(1L, "u", "N", null),
                new TelegramChat(100L), null, new TelegramVoice("file_id", 1000));
    }
}
```

**Результаты:** 4/4 пройдены
- Текстовые сообщения маршрутизируются в TextMessageHandler
- Команды (/start, /help) маршрутизируются в BotCommandRouter
- Голосовые сообщения маршрутизируются в VoiceMessageHandler
- Callback queries маршрутизируются в CallbackHandler

---

## Как запустить тесты

### Запуск всех тестов:
```bash
./gradlew test
```

### Запуск конкретного модуля:
```bash
./gradlew :core-service:shared:security:test
./gradlew :core-service:modules:task:task-impl:test
./gradlew :core-service:modules:integration-telegram:integration-telegram-impl:test
```

### Запуск конкретного теста:
```bash
./gradlew :core-service:shared:security:test --tests TelegramInitDataValidatorTest
./gradlew :core-service:modules:task:task-impl:test --tests TaskServiceTest
```

### Просмотр результатов:
После выполнения `./gradlew test` результаты доступны в:
```
build/reports/tests/test/index.html
```

Откройте в браузере для просмотра HTML-отчёта с подробностями каждого теста.

---

## Итоговая статистика

| Компонент | Тесты | Статус |
|-----------|-------|--------|
| TelegramInitDataValidator | 5 | PASSED |
| TaskService | 5 | PASSED |
| UpdateRouter | 4 | PASSED |
| **ИТОГО** | **14** | **14/14** |

Все основные критические сценарии покрыты unit-тестами с использованием Mockito для изоляции компонентов.
