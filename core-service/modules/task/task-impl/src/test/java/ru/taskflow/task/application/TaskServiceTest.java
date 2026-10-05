package ru.taskflow.task.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import ru.taskflow.audit.api.AuditEventType;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.assistant.api.FocusHintGenerator;
import ru.taskflow.assistant.api.FocusPlannedDateGenerator;
import ru.taskflow.shared.exception.ValidationException;
import ru.taskflow.task.api.RecurrenceType;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.RecurrenceRule;
import ru.taskflow.task.api.dto.ReminderResponse;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.task.api.exception.TaskNotFoundException;
import ru.taskflow.task.infrastructure.FocusHoursGateConfig;
import ru.taskflow.task.infrastructure.FocusHintConfig;
import ru.taskflow.task.infrastructure.persistence.*;
import ru.taskflow.user.api.UserService;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    @Mock
    private TaskReminderService taskReminderService;
    @Mock
    private ReminderRepository reminderRepository;
    @Mock
    private RecurrenceRepository recurrenceRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private GroupStyleResolver groupStyleResolver;
    @Mock
    private UserService userService;
    @Mock
    private FocusHintGenerator focusHintGenerator;
    @Mock
    private FocusPlannedDateGenerator focusPlannedDateGenerator;

    // Реальный Clock.fixed, не мок — прокрутке следующего вхождения (А) нужно
    // настоящее сравнение дат, а не настраивать заглушку под каждый шаг.
    // Зафиксирован заведомо раньше дат во всех тестах ниже (те — январь-февраль
    // 2026), чтобы "опоздание на много периодов" можно было проверить отдельно
    // от уже существующих тестов на один шаг.
    private static final Instant NOW = Instant.parse("2025-06-01T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    // Реальный, не мок — правило ранжирования проверяется отдельно
    // (FocusTaskRankerTest), здесь важно только что TaskServiceImpl его
    // действительно вызывает и сохраняет отметку показа.
    private final FocusTaskRanker focusTaskRanker = new FocusTaskRanker();

    private TaskServiceImpl taskService;

    private final UUID userId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // По умолчанию зона пользователя — UTC, чтобы существующие тесты (даты
        // подобраны под фиксированный UTC-clock) не зависели от зоны. Тест на
        // саму зону переопределяет стаб под конкретный сценарий.
        lenient().when(userService.getTimezone(any())).thenReturn(ZoneOffset.UTC);
        // Пустой список корней по умолчанию — ворота §5.4 не должны влиять на
        // тесты, которые их не касаются; тесты самих ворот строят свой
        // TaskServiceImpl с непустой конфигурацией отдельно.
        taskService = new TaskServiceImpl(taskRepository, groupRepository, tagRepository, taskMapper,
                taskReminderService, reminderRepository, recurrenceRepository, auditService, groupStyleResolver,
                clock, focusTaskRanker, userService, new FocusHoursGateConfig(), new FocusHintConfig(),
                focusHintGenerator, focusPlannedDateGenerator);
    }

    @Test
    void create_savesAndReturnsResponse() {
        var request = new CreateTaskRequest("купить молоко", null, null, null, null, null, List.of(), null, null);
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
    void create_preservesPlannedDateWithoutMakingItDeadline() {
        var plannedDate = OffsetDateTime.parse("2026-10-07T00:00:00+03:00");
        var request = new CreateTaskRequest("разобрать заметки", null, null, null, null, null,
                List.of(), 15, null, null, false, plannedDate);
        ArgumentCaptor<TaskJpaEntity> captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        when(taskRepository.save(captor.capture())).thenAnswer(invocation -> {
            TaskJpaEntity saved = invocation.getArgument(0);
            saved.setId(taskId);
            return saved;
        });
        when(taskMapper.toResponse(any())).thenReturn(mockResponse(taskId, "разобрать заметки"));

        taskService.create(userId, request);

        var saved = captor.getValue();
        assertThat(saved.getPlannedDate()).isEqualTo(plannedDate);
        assertThat(saved.getPlannedDateSetAt()).isEqualTo(OffsetDateTime.now(clock));
        assertThat(saved.getDeadline()).isNull();
        verify(taskReminderService).planForDeadline(userId, saved);
        verifyNoMoreInteractions(taskReminderService);
    }

    @Test
    void create_leavesPlannedDateUnsetWhenOmitted() {
        var request = new CreateTaskRequest("разобрать заметки", null, null, null, null, null,
                List.of(), null, null);
        ArgumentCaptor<TaskJpaEntity> captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        when(taskRepository.save(captor.capture())).thenReturn(taskEntity());
        when(taskMapper.toResponse(any())).thenReturn(mockResponse(taskId, "разобрать заметки"));

        taskService.create(userId, request);

        assertThat(captor.getValue().getPlannedDate()).isNull();
        assertThat(captor.getValue().getPlannedDateSetAt()).isNull();
    }

    // Б1: настойчивость ставит человек, через обычный запрос создания/правки —
    // модель это поле не видит (контур ассистента не участвует).
    @Test
    void create_setsPersistentReminderFromRequest() {
        var request = new CreateTaskRequest("принять лекарство", null, null, null, null, null, List.of(), null,
                null, null, true);
        ArgumentCaptor<TaskJpaEntity> captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        when(taskRepository.save(captor.capture())).thenReturn(taskEntity());
        when(taskMapper.toResponse(any())).thenReturn(mockResponse(taskId, "принять лекарство"));

        taskService.create(userId, request);

        assertThat(captor.getValue().isPersistentReminder()).isTrue();
    }

    @Test
    void update_setsPersistentReminderFlag() {
        var existing = taskEntity();
        existing.setId(taskId);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(existing));
        when(taskRepository.save(existing)).thenReturn(existing);
        when(taskMapper.toResponse(existing)).thenReturn(mockResponse(taskId, "задача"));
        var request = new UpdateTaskRequest(null, null, null, null, null, null, null, null, null, null, null, true);

        taskService.update(userId, taskId, request);

        assertThat(existing.isPersistentReminder()).isTrue();
    }

    // Б1/Б4: снятие флага гасит цепочку — только когда флаг реально был
    // включён и его явно выключают, а не при любом обновлении задачи.
    @Test
    void update_turningPersistenceOff_cancelsPersistentChain() {
        var existing = taskEntity();
        existing.setId(taskId);
        existing.setPersistentReminder(true);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(existing));
        when(taskRepository.save(existing)).thenReturn(existing);
        when(taskMapper.toResponse(existing)).thenReturn(mockResponse(taskId, "задача"));
        var request = new UpdateTaskRequest(null, null, null, null, null, null, null, null, null, null, null, false);

        taskService.update(userId, taskId, request);

        assertThat(existing.isPersistentReminder()).isFalse();
        verify(taskReminderService).cancelPersistentChain(taskId);
    }

    @Test
    void update_withoutTouchingPersistentReminder_doesNotCancelChain() {
        var existing = taskEntity();
        existing.setId(taskId);
        existing.setPersistentReminder(true);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(existing));
        when(taskRepository.save(existing)).thenReturn(existing);
        when(taskMapper.toResponse(existing)).thenReturn(mockResponse(taskId, "задача"));
        var request = new UpdateTaskRequest("новое название", null, null, null, null, null, null, null, null, null,
                null, null);

        taskService.update(userId, taskId, request);

        assertThat(existing.isPersistentReminder()).isTrue();
        verify(taskReminderService, never()).cancelPersistentChain(any());
    }

    @Test
    void snoozeReminder_delegatesToTaskReminderService() {
        UUID reminderId = UUID.randomUUID();
        OffsetDateTime until = OffsetDateTime.parse("2026-08-30T09:00:00Z");
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(taskEntity()));

        taskService.snoozeReminder(userId, taskId, reminderId, until);

        verify(taskReminderService).snoozeReminder(taskId, reminderId, until);
    }

    @Test
    void create_delegatesReminderPlanningToTaskReminderService() {
        // Что именно решается (планировать ли, когда, сколько) — забота
        // TaskReminderService, проверено его собственными тестами; здесь
        // важно только то, что TaskServiceImpl ему это делегирует.
        var deadline = OffsetDateTime.parse("2026-08-25T10:00:00Z");
        var request = new CreateTaskRequest("сдать курсовую", null, null, deadline, null, null, List.of(), null, null);
        var entity = new TaskJpaEntity();
        entity.setId(taskId);
        entity.setUserId(userId);
        entity.setTitle("сдать курсовую");
        entity.setDeadline(deadline);
        var response = mockResponse(taskId, "сдать курсовую");

        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.create(userId, request);

        verify(taskReminderService).planForDeadline(userId, entity);
    }

    // --- Блок А1: правило повтора хранится ---

    @Test
    void create_savesRecurrenceRule_whenProvided() {
        var entity = taskEntity();
        entity.setId(taskId);
        var rule = new RecurrenceRule(RecurrenceType.DAILY, 2, null, null, null);
        var request = new CreateTaskRequest("зарядка", null, null, null, null, null, List.of(), null, null, rule);

        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(mockResponse(taskId, "зарядка"));
        when(recurrenceRepository.findById(taskId)).thenReturn(Optional.empty());
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = taskService.create(userId, request);

        var captor = ArgumentCaptor.forClass(RecurrenceJpaEntity.class);
        verify(recurrenceRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(RecurrenceType.DAILY);
        assertThat(captor.getValue().getIntervalN()).isEqualTo(2);
        assertThat(result.recurrence().type()).isEqualTo(RecurrenceType.DAILY);
    }

    @Test
    void create_rejectsCustomRecurrence() {
        var entity = taskEntity();
        entity.setId(taskId);
        var rule = new RecurrenceRule(RecurrenceType.CUSTOM, null, null, null, null);
        var request = new CreateTaskRequest("задача", null, null, null, null, null, List.of(), null, null, rule);

        when(taskRepository.save(any())).thenReturn(entity);

        assertThatThrownBy(() -> taskService.create(userId, request))
                .isInstanceOf(ValidationException.class);
        verify(recurrenceRepository, never()).save(any());
    }

    @Test
    void create_rejectsMonthlyWithoutDayOfMonth() {
        var entity = taskEntity();
        entity.setId(taskId);
        var rule = new RecurrenceRule(RecurrenceType.MONTHLY, null, null, null, null);
        var request = new CreateTaskRequest("задача", null, null, null, null, null, List.of(), null, null, rule);

        when(taskRepository.save(any())).thenReturn(entity);

        assertThatThrownBy(() -> taskService.create(userId, request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void findById_returnsTask_whenExists() {
        var entity = taskEntity();
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
    void update_changesTitle() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest("новый заголовок", null, null, null, null, null, null, null, null);
        var response = mockResponse(taskId, "новый заголовок");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.update(userId, taskId, request);

        assertThat(entity.getTitle()).isEqualTo("новый заголовок");
        assertThat(result.title()).isEqualTo("новый заголовок");
    }

    @Test
    void update_explicitlyClearsGroupAndPlannedDateInAudit() {
        var entity = taskEntity();
        var group = new GroupJpaEntity();
        group.setId(UUID.randomUUID());
        entity.setGroup(group);
        entity.setPlannedDate(OffsetDateTime.parse("2026-10-07T00:00:00Z"));
        entity.setPlannedDateSetAt(OffsetDateTime.now(clock));
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(entity)).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(mockResponse(taskId, "задача"));
        var request = new UpdateTaskRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, true, true);

        taskService.update(userId, taskId, request);

        assertThat(entity.getGroup()).isNull();
        assertThat(entity.getPlannedDate()).isNull();
        assertThat(entity.getPlannedDateSetAt()).isNull();
        assertThat(entity.getUserId()).isEqualTo(userId);
        verify(auditService).record(eq(userId), eq(taskId), eq(AuditEventType.UPDATED),
                argThat(delta -> delta != null && delta.size() == 2
                        && delta.containsKey("groupId") && delta.get("groupId") == null
                        && delta.containsKey("plannedDate") && delta.get("plannedDate") == null));
        verifyNoInteractions(groupRepository, taskReminderService);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(booleans = false)
    void update_keepsGroupAndPlannedDateWithoutExplicitClear(Boolean clear) {
        var entity = taskEntity();
        var group = new GroupJpaEntity();
        group.setId(UUID.randomUUID());
        var planned = OffsetDateTime.parse("2026-10-07T00:00:00Z");
        var setAt = OffsetDateTime.now(clock);
        entity.setGroup(group);
        entity.setPlannedDate(planned);
        entity.setPlannedDateSetAt(setAt);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(entity)).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(mockResponse(taskId, "задача"));
        var request = new UpdateTaskRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, clear, clear);

        taskService.update(userId, taskId, request);

        assertThat(entity.getGroup()).isSameAs(group);
        assertThat(entity.getPlannedDate()).isEqualTo(planned);
        assertThat(entity.getPlannedDateSetAt()).isEqualTo(setAt);
        verify(auditService).record(userId, taskId, AuditEventType.UPDATED, null);
        verifyNoInteractions(groupRepository, taskReminderService);
    }

    @Test
    void update_clearCannotTouchAnotherUsersTask() {
        var otherUserId = UUID.randomUUID();
        when(taskRepository.findByIdAndUserId(taskId, otherUserId)).thenReturn(Optional.empty());
        var request = new UpdateTaskRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, true, true);

        assertThatThrownBy(() -> taskService.update(otherUserId, taskId, request))
                .isInstanceOf(TaskNotFoundException.class);

        verify(taskRepository).findByIdAndUserId(taskId, otherUserId);
        verifyNoMoreInteractions(taskRepository);
        verifyNoInteractions(auditService, groupRepository, taskReminderService);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = TaskStatus.class, names = {"TODO", "IN_PROGRESS"})
    void update_returningDoneTaskToActiveClearsCompletionTime(TaskStatus status) {
        var entity = taskEntity();
        entity.setStatus(TaskStatus.DONE);
        entity.setCompletedAt(OffsetDateTime.now(clock));
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(entity)).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(mockResponse(taskId, "задача"));
        var request = new UpdateTaskRequest(null, null, null, status, null, null, null, null, null);

        taskService.update(userId, taskId, request);

        assertThat(entity.getStatus()).isEqualTo(status);
        assertThat(entity.getCompletedAt()).isNull();
        verifyNoInteractions(taskReminderService);
    }

    @Test
    void update_recordsChangedFieldsInAudit() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest("новое название", null, null, null, null, null, null, null, null);
        var response = mockResponse(taskId, "новое название");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        ArgumentCaptor<Map> deltaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(userId), eq(taskId), eq(AuditEventType.UPDATED), deltaCaptor.capture());
        assertThat(deltaCaptor.getValue()).containsEntry("title", "новое название");
    }

    @Test
    void update_changesGroupToExisting() {
        var entity = taskEntity();
        var existingGroup = new GroupJpaEntity();
        existingGroup.setId(UUID.randomUUID());
        existingGroup.setUserId(userId);
        existingGroup.setName("Работа");
        var request = new UpdateTaskRequest(null, null, null, null, null, null, "Работа", null, null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(groupRepository.findByUserIdAndName(userId, "Работа")).thenReturn(Optional.of(existingGroup));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        assertThat(entity.getGroup()).isEqualTo(existingGroup);
        verify(groupRepository, never()).save(any());
    }

    @Test
    void update_createsGroupWhenMissing() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest(null, null, null, null, null, null, "Новая группа", null, null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(groupRepository.findByUserIdAndName(userId, "Новая группа")).thenReturn(Optional.empty());
        when(groupStyleResolver.resolve("Новая группа")).thenReturn(new GroupStyleResolver.GroupStyle("blue", "folder"));
        when(groupRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        ArgumentCaptor<GroupJpaEntity> groupCaptor = ArgumentCaptor.forClass(GroupJpaEntity.class);
        verify(groupRepository).save(groupCaptor.capture());
        assertThat(groupCaptor.getValue().getName()).isEqualTo("Новая группа");
        assertThat(entity.getGroup()).isEqualTo(groupCaptor.getValue());
    }

    @Test
    void update_setsColorAndIconOnAutoCreatedGroup() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest(null, null, null, null, null, null, "Спорт", null, null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(groupRepository.findByUserIdAndName(userId, "Спорт")).thenReturn(Optional.empty());
        when(groupStyleResolver.resolve("Спорт")).thenReturn(new GroupStyleResolver.GroupStyle("cyan", "run"));
        when(groupRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        ArgumentCaptor<GroupJpaEntity> groupCaptor = ArgumentCaptor.forClass(GroupJpaEntity.class);
        verify(groupRepository).save(groupCaptor.capture());
        assertThat(groupCaptor.getValue().getColor()).isEqualTo("cyan");
        assertThat(groupCaptor.getValue().getIcon()).isEqualTo("run");
    }

    @Test
    void update_recordsGroupChangeInAudit() {
        var entity = taskEntity();
        var existingGroup = new GroupJpaEntity();
        existingGroup.setId(UUID.randomUUID());
        existingGroup.setUserId(userId);
        existingGroup.setName("Работа");
        var request = new UpdateTaskRequest(null, null, null, null, null, null, "Работа", null, null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(groupRepository.findByUserIdAndName(userId, "Работа")).thenReturn(Optional.of(existingGroup));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        ArgumentCaptor<Map> deltaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(userId), eq(taskId), eq(AuditEventType.UPDATED), deltaCaptor.capture());
        assertThat(deltaCaptor.getValue()).containsEntry("groupId", existingGroup.getId());
    }

    @Test
    void complete_setsStatusDone() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));

        taskService.complete(userId, taskId);

        assertThat(entity.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(entity.getCompletedAt()).isNotNull();
    }

    @Test
    void complete_cancelsNotifications() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));

        taskService.complete(userId, taskId);

        verify(taskReminderService).cancelForTask(taskId);
    }

    // --- Блок А2: следующее вхождение при закрытии ---

    @Test
    void complete_withoutRecurrence_doesNotCreateNextOccurrence() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(recurrenceRepository.findById(taskId)).thenReturn(Optional.empty());

        taskService.complete(userId, taskId);

        verify(taskRepository, times(1)).save(any());
    }

    @Test
    void complete_withDailyRecurrence_anchorsOnScheduledDeadline_notOnActualCompletionTime() {
        var deadline = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        var entity = taskEntity();
        entity.setDeadline(deadline);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.DAILY, 1, null, null, null)));

        // Задача закрыта на много дней позже срока — опоздание не должно влиять на дату следующего вхождения.
        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskRepository, times(2)).save(captor.capture());
        TaskJpaEntity nextOccurrence = captor.getAllValues().get(1);
        assertThat(nextOccurrence.getDeadline()).isEqualTo(deadline.plusDays(1));
    }

    @Test
    void complete_withWeeklyRecurrenceAndDaysOfWeek_picksNextMatchingWeekday() {
        // Пятница — следующее совпадение из {вторник, четверг} — вторник через 4 дня.
        var deadline = OffsetDateTime.parse("2026-01-02T10:00:00Z"); // пятница
        var entity = taskEntity();
        entity.setDeadline(deadline);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId)).thenReturn(Optional.of(
                recurrenceEntity(RecurrenceType.WEEKLY, 1, "2,4", null, null)));

        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getDeadline()).isEqualTo(deadline.plusDays(4));
    }

    @Test
    void complete_withWeekdaysRecurrence_skipsWeekend() {
        var friday = OffsetDateTime.parse("2026-01-02T10:00:00Z");
        var entity = taskEntity();
        entity.setDeadline(friday);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.WEEKDAYS, 1, null, null, null)));

        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getDeadline()).isEqualTo(friday.plusDays(3));
    }

    @Test
    void complete_withMonthlyRecurrence_clampsDayOfMonthToShorterMonth() {
        var jan31 = OffsetDateTime.parse("2026-01-31T10:00:00Z");
        var entity = taskEntity();
        entity.setDeadline(jan31);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.MONTHLY, 1, null, 31, null)));

        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getDeadline()).isEqualTo(OffsetDateTime.parse("2026-02-28T10:00:00Z"));
    }

    @Test
    void complete_recurrenceEndsAt_stopsChainWhenNextDateIsAfterEnd() {
        var deadline = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        var endsAt = OffsetDateTime.parse("2026-01-01T23:59:59Z");
        var entity = taskEntity();
        entity.setDeadline(deadline);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.DAILY, 1, null, null, endsAt)));

        taskService.complete(userId, taskId);

        verify(taskRepository, times(1)).save(any());
    }

    // --- Блок А: прокрутка вместо одного шага при опоздании на много периодов ---

    @Test
    void complete_dailyRecurrence_lateByMultiplePeriods_rollsForwardPastNow() {
        // NOW = 2025-06-01T00:00:00Z. anchor на 4 дня раньше и со сдвигом на 9
        // часов, чтобы ни один промежуточный кандидат не совпал с NOW точно.
        // Дни (T09:00): 28.05, 29.05, 30.05, 31.05, 01.06 — первый кандидат
        // 01.06T09:00 первым оказывается не раньше NOW.
        var anchor = OffsetDateTime.parse("2025-05-28T09:00:00Z");
        var entity = taskEntity();
        entity.setDeadline(anchor);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.DAILY, 1, null, null, null)));

        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getDeadline()).isEqualTo(OffsetDateTime.parse("2025-06-01T09:00:00Z"));
    }

    @Test
    void complete_weeklyRecurrence_lateByMultiplePeriods_rollsForwardPastNow() {
        // Каждую неделю от 04.05 (вс, T10:00): 11.05, 18.05, 25.05, 01.06 —
        // первый кандидат не раньше NOW (01.06T00:00) — 01.06T10:00.
        var anchor = OffsetDateTime.parse("2025-05-04T10:00:00Z");
        var entity = taskEntity();
        entity.setDeadline(anchor);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.WEEKLY, 1, null, null, null)));

        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getDeadline()).isEqualTo(OffsetDateTime.parse("2025-06-01T10:00:00Z"));
    }

    @Test
    void complete_weeklyRecurrenceWithDaysOfWeek_lateByMultiplePeriods_rollsForwardPastNow_keepingWeekdayAnchor() {
        // Вт/Чт от 20.05 (вт, T10:00): 22.05(чт), 27.05(вт), 29.05(чт), 03.06(вт) —
        // первый не раньше NOW (01.06T00:00) — 03.06(вт), не 29.05 (тот ещё до NOW).
        // Якорь дня недели (вторник/четверг) на каждом шаге сохраняется.
        var anchor = OffsetDateTime.parse("2025-05-20T10:00:00Z");
        var entity = taskEntity();
        entity.setDeadline(anchor);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId)).thenReturn(Optional.of(
                recurrenceEntity(RecurrenceType.WEEKLY, 1, "2,4", null, null)));

        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskRepository, times(2)).save(captor.capture());
        var next = captor.getAllValues().get(1).getDeadline();
        assertThat(next).isEqualTo(OffsetDateTime.parse("2025-06-03T10:00:00Z"));
        assertThat(next.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.TUESDAY);
    }

    @Test
    void complete_weekdaysRecurrence_lateByMultiplePeriods_rollsForwardPastNow_skippingWeekends() {
        // От пятницы 23.05: 26.05(пн),27,28,29,30(пт),02.06(пн) — первый не
        // раньше NOW (01.06T00:00) — 02.06(пн), с пропуском выходных 31.05-01.06.
        var anchor = OffsetDateTime.parse("2025-05-23T10:00:00Z");
        var entity = taskEntity();
        entity.setDeadline(anchor);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.WEEKDAYS, 1, null, null, null)));

        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskRepository, times(2)).save(captor.capture());
        var next = captor.getAllValues().get(1).getDeadline();
        assertThat(next).isEqualTo(OffsetDateTime.parse("2025-06-02T10:00:00Z"));
        assertThat(next.getDayOfWeek()).isNotIn(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY);
    }

    @Test
    void complete_monthlyRecurrence_lateByMultiplePeriods_rollsForwardPastNow_keepingDayOfMonthAnchor() {
        // Каждый месяц 15-го от 15.04: 15.05, 15.06 — первый не раньше NOW
        // (01.06T00:00) — 15.06, майское вхождение пропущено целиком.
        var anchor = OffsetDateTime.parse("2025-04-15T10:00:00Z");
        var entity = taskEntity();
        entity.setDeadline(anchor);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.MONTHLY, 1, null, 15, null)));

        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskRepository, times(2)).save(captor.capture());
        var next = captor.getAllValues().get(1).getDeadline();
        assertThat(next).isEqualTo(OffsetDateTime.parse("2025-06-15T10:00:00Z"));
        assertThat(next.getDayOfMonth()).isEqualTo(15);
    }

    @Test
    void complete_recurrenceEndsAt_stopsChainDuringRollForward_notJustOnFirstStep() {
        // anchor как в daily-тесте выше, но ends_at попадает между первым и
        // вторым шагом прокрутки (после 29.05, до 30.05) — цепочка должна
        // остановиться там, а не докрутиться до NOW.
        var anchor = OffsetDateTime.parse("2025-05-28T09:00:00Z");
        var endsAt = OffsetDateTime.parse("2025-05-30T00:00:00Z");
        var entity = taskEntity();
        entity.setDeadline(anchor);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.DAILY, 1, null, null, endsAt)));

        taskService.complete(userId, taskId);

        verify(taskRepository, times(1)).save(any());
    }

    @Test
    void update_toCancelled_doesNotCreateNextOccurrence() {
        var entity = taskEntity();
        entity.setDeadline(OffsetDateTime.parse("2026-01-01T10:00:00Z"));
        var request = new UpdateTaskRequest(null, null, null, TaskStatus.CANCELLED, null, null, null, null, null);

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(mockResponse(taskId, "задача"));
        when(recurrenceRepository.findById(taskId)).thenReturn(Optional.empty());

        taskService.update(userId, taskId, request);

        verify(taskRepository, times(1)).save(any());
    }

    @Test
    void complete_inheritsContentFields_notDeadlineOrSource() {
        var group = new GroupJpaEntity();
        group.setId(UUID.randomUUID());
        var entity = taskEntity();
        entity.setDeadline(OffsetDateTime.parse("2026-01-01T10:00:00Z"));
        entity.setDescription("подробности");
        entity.setPriority(TaskPriority.HIGH);
        entity.setGroup(group);
        entity.setEstimateMinutes(45);
        entity.setSource(ru.taskflow.task.api.TaskSource.MANUAL);

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.DAILY, 1, null, null, null)));

        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskRepository, times(2)).save(captor.capture());
        TaskJpaEntity next = captor.getAllValues().get(1);
        assertThat(next.getTitle()).isEqualTo(entity.getTitle());
        assertThat(next.getDescription()).isEqualTo("подробности");
        assertThat(next.getPriority()).isEqualTo(TaskPriority.HIGH);
        assertThat(next.getGroup()).isEqualTo(group);
        assertThat(next.getEstimateMinutes()).isEqualTo(45);
        assertThat(next.getSource()).isEqualTo(ru.taskflow.task.api.TaskSource.RECURRENCE);
    }

    @Test
    void complete_newOccurrence_getsRelativeReminderThroughPlanForDeadline() {
        var entity = taskEntity();
        entity.setDeadline(OffsetDateTime.parse("2026-01-01T10:00:00Z"));
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.DAILY, 1, null, null, null)));

        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskReminderService).planForDeadline(eq(userId), captor.capture());
        assertThat(captor.getValue()).isNotSameAs(entity);
        assertThat(captor.getValue().getDeadline()).isEqualTo(OffsetDateTime.parse("2026-01-02T10:00:00Z"));
    }

    @Test
    void complete_anchorsOnPlannedDate_whenNoDeadline() {
        var plannedDate = OffsetDateTime.parse("2026-01-01T09:00:00Z");
        var entity = taskEntity();
        entity.setPlannedDate(plannedDate);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.DAILY, 1, null, null, null)));

        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskRepository, times(2)).save(captor.capture());
        TaskJpaEntity next = captor.getAllValues().get(1);
        assertThat(next.getPlannedDate()).isEqualTo(plannedDate.plusDays(1));
        assertThat(next.getDeadline()).isNull();
    }

    @Test
    void complete_noAnchorAtAll_stillCreatesNextOccurrenceWithoutDate() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.findById(taskId))
                .thenReturn(Optional.of(recurrenceEntity(RecurrenceType.DAILY, 1, null, null, null)));

        taskService.complete(userId, taskId);

        var captor = ArgumentCaptor.forClass(TaskJpaEntity.class);
        verify(taskRepository, times(2)).save(captor.capture());
        TaskJpaEntity next = captor.getAllValues().get(1);
        assertThat(next.getDeadline()).isNull();
        assertThat(next.getPlannedDate()).isNull();
    }

    private RecurrenceJpaEntity recurrenceEntity(RecurrenceType type, int intervalN, String daysOfWeek,
                                                  Integer dayOfMonth, OffsetDateTime endsAt) {
        var e = new RecurrenceJpaEntity();
        e.setTaskId(taskId);
        e.setType(type);
        e.setIntervalN(intervalN);
        e.setDaysOfWeek(daysOfWeek);
        e.setDayOfMonth(dayOfMonth);
        e.setEndsAt(endsAt);
        return e;
    }

    @Test
    void delete_marksAsDeleted() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));

        taskService.delete(userId, taskId);

        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getDeletedAt()).isNotNull();
    }

    @Test
    void delete_cancelsNotifications() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));

        taskService.delete(userId, taskId);

        verify(taskReminderService).cancelForTask(taskId);
    }

    @Test
    void update_cancelsNotifications_whenStatusBecomesCancelled() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest(null, null, null, TaskStatus.CANCELLED, null, null, null, null, null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        verify(taskReminderService).cancelForTask(taskId);
        verify(taskReminderService, never()).planForDeadline(any(), any());
    }

    @Test
    void update_doesNotCancelNotifications_whenOnlyUnrelatedFieldChanges() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest(null, null, TaskPriority.HIGH, null, null, null, null, null, null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        verify(taskReminderService, never()).cancelForTask(any());
    }

    @Test
    void delete_throwsNotFound_whenMissing() {
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.delete(userId, taskId))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void findAssistantContext_returnsTasksFromRepository() {
        var entity = taskEntity();
        var response = mockResponse(taskId, "купить молоко");

        when(taskRepository.findAssistantContext(eq(userId), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(entity));
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.findAssistantContext(userId, 80);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("купить молоко");
    }

    @Test
    void findAssistantContext_passesLimitAsPageSize() {
        when(taskRepository.findAssistantContext(eq(userId), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        taskService.findAssistantContext(userId, 25);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findAssistantContext(eq(userId), any(OffsetDateTime.class), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    void search_returnsMatchesByTitle() {
        var entity = new TaskJpaEntity();
        entity.setUserId(userId);
        entity.setTitle("Купить корм коту");
        var response = mockResponse(taskId, "Купить корм коту");

        when(taskRepository.search(eq(userId), eq("корм"), eq(false), any(Pageable.class)))
                .thenReturn(List.of(entity));
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.search(userId, "корм", false, 20);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("Купить корм коту");
    }

    @Test
    void search_returnsEmptyForBlankQuery() {
        var result = taskService.search(userId, "  ", false, 20);

        assertThat(result).isEmpty();
        verifyNoInteractions(taskRepository);
    }

    @Test
    void search_capsLimit() {
        taskService.search(userId, "корм", false, 500);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).search(any(), any(), anyBoolean(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void getStats_includesCompletedRowsReturnedByRepository() {
        var completedAt = OffsetDateTime.parse("2026-08-15T10:00:00Z");
        when(taskRepository.findStatsRows(eq(userId), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.<Object[]>of(new Object[] {
                        completedAt.minusDays(1),
                        completedAt,
                        null,
                        "DONE"
                }));

        var result = taskService.getStats(userId);

        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().getFirst().completedAt()).isEqualTo(completedAt.withOffsetSameInstant(ZoneOffset.UTC));
        assertThat(result.tasks().getFirst().status()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void transferOwnership_movesTasksGroupsAndTags() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(taskRepository.reassignOwner(from, to)).thenReturn(120);
        when(groupRepository.reassignOwner(from, to)).thenReturn(5);
        when(tagRepository.reassignOwner(from, to)).thenReturn(3);

        var result = taskService.transferOwnership(from, to);

        assertThat(result.tasks()).isEqualTo(120);
        assertThat(result.groups()).isEqualTo(5);
        assertThat(result.tags()).isEqualTo(3);
    }

    @Test
    void getUpcomingFocusTasks_returnsTasksFromRepository() {
        var entity = taskEntity();
        var response = mockResponse(taskId, "задача на следующей неделе");

        when(taskRepository.findUpcomingFocusTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(List.of(entity));
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.getUpcomingFocusTasks(userId, null);

        assertThat(result.tasks()).containsExactly(response);
    }

    @Test
    void getUpcomingFocusTasks_capsAtThree() {
        var entities = List.of(taskEntity(), taskEntity(), taskEntity(), taskEntity());
        when(taskRepository.findUpcomingFocusTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(entities);
        when(taskMapper.toResponse(any())).thenReturn(mockResponse(taskId, "задача"));

        var result = taskService.getUpcomingFocusTasks(userId, null);

        assertThat(result.tasks()).hasSize(3);
    }

    // --- Пункт Б: отбор фокуса по доступному времени ---

    @Test
    void getFocusTasks_withoutAvailableMinutes_includesTasksRegardlessOfEstimate() {
        var withEstimate = taskEntity();
        withEstimate.setEstimateMinutes(120);
        when(taskRepository.findFocusTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(List.of(withEstimate));
        when(taskMapper.toResponse(withEstimate)).thenReturn(mockResponse(taskId, "задача"));

        var result = taskService.getFocusTasks(userId, null);

        assertThat(result.tasks()).hasSize(1);
    }

    @Test
    void getFocusTasks_withAvailableMinutes_excludesTaskThatDoesNotFit() {
        var longTask = taskEntity();
        longTask.setEstimateMinutes(120);
        when(taskRepository.findFocusTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(List.of(longTask));

        var result = taskService.getFocusTasks(userId, 15);

        assertThat(result.tasks()).isEmpty();
    }

    @Test
    void getFocusTasks_withAvailableMinutes_neverExcludesTaskWithoutEstimate() {
        var noEstimate = taskEntity();
        when(taskRepository.findFocusTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(List.of(noEstimate));
        when(taskMapper.toResponse(noEstimate)).thenReturn(mockResponse(taskId, "задача"));

        var result = taskService.getFocusTasks(userId, 15);

        assertThat(result.tasks()).hasSize(1);
    }

    @Test
    void getFocusTasks_embedsReminders_notJustEmptyList() {
        var entity = taskEntity();
        entity.setId(taskId);
        when(taskRepository.findFocusTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(List.of(entity));
        when(taskMapper.toResponse(entity)).thenReturn(mockResponse(taskId, "задача"));
        var reminderEntity = new ReminderJpaEntity();
        reminderEntity.setTask(entity);
        var reminderResponse = new ReminderResponse(UUID.randomUUID(), OffsetDateTime.now(), "PENDING");
        when(reminderRepository.findByTaskIdInAndStatusOrderByFireAtAsc(any(), eq(ReminderStatus.PENDING)))
                .thenReturn(List.of(reminderEntity));
        when(taskMapper.toReminderResponse(reminderEntity)).thenReturn(reminderResponse);

        var result = taskService.getFocusTasks(userId, null);

        assertThat(result.tasks().getFirst().reminders()).containsExactly(reminderResponse);
    }

    // --- Блок А: ритм пересмотра бессрочных задач — отметка показа и её отсутствие для отсеянных ---

    @Test
    void getFocusTasks_marksReturnedTasksAsShownNow() {
        var shown = taskEntity();
        when(taskRepository.findFocusTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(List.of(shown));
        when(taskMapper.toResponse(shown)).thenReturn(mockResponse(taskId, "задача"));

        taskService.getFocusTasks(userId, null);

        assertThat(shown.getLastShownInFocusAt()).isEqualTo(OffsetDateTime.now(clock));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskJpaEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(shown);
    }

    // Отметка ставится в момент выдачи — задача, которую отсеял лимит
    // экрана (не более 3), показана не была и отметку не получает.
    @Test
    void getFocusTasks_doesNotMarkTasksExcludedByScreenLimit() {
        var shownEntities = List.of(taskEntity(), taskEntity(), taskEntity());
        var excluded = taskEntity();
        List<TaskJpaEntity> all = new ArrayList<>(shownEntities);
        all.add(excluded);
        when(taskRepository.findFocusTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(all);
        when(taskMapper.toResponse(any())).thenReturn(mockResponse(taskId, "задача"));

        taskService.getFocusTasks(userId, null);

        assertThat(excluded.getLastShownInFocusAt()).isNull();
    }

    // --- §5.4: задача, привязанная к часам работы учреждения, не поднимается
    // в фокус вне окна — и, как отсеянная лимитом экрана, не получает
    // отметку показа, поэтому всплывёт сама. NOW (общий clock класса)
    // зафиксирован на 2025-06-01T00:00:00Z — полночь, вне любого разумного
    // окна 9-19 при зоне UTC. Каждый тест строит свой TaskServiceImpl с
    // отдельным FocusHoursGateConfig — общий из setUp() держит пустой
    // список корней, чтобы остальные тесты класса от ворот не зависели.

    @Test
    void getFocusTasks_excludesTiedTaskOutsideWindow_andDoesNotMarkItShown() {
        var config = new FocusHoursGateConfig();
        config.setRoots(List.of("банк"));
        var gated = new TaskServiceImpl(taskRepository, groupRepository, tagRepository, taskMapper,
                taskReminderService, reminderRepository, recurrenceRepository, auditService, groupStyleResolver,
                clock, focusTaskRanker, userService, config, new FocusHintConfig(), focusHintGenerator,
                focusPlannedDateGenerator);
        var tied = taskEntity();
        tied.setTitle("Позвонить в банк");
        when(taskRepository.findFocusTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(List.of(tied));

        var result = gated.getFocusTasks(userId, null);

        assertThat(result.tasks()).isEmpty();
        assertThat(tied.getLastShownInFocusAt()).isNull();
        verify(taskRepository).saveAll(List.of());
    }

    @Test
    void getFocusTasks_includesTiedTaskInsideWindow() {
        var config = new FocusHoursGateConfig();
        config.setRoots(List.of("банк"));
        var dayClock = Clock.fixed(Instant.parse("2025-06-02T12:00:00Z"), ZoneOffset.UTC);
        var gated = new TaskServiceImpl(taskRepository, groupRepository, tagRepository, taskMapper,
                taskReminderService, reminderRepository, recurrenceRepository, auditService, groupStyleResolver,
                dayClock, focusTaskRanker, userService, config, new FocusHintConfig(), focusHintGenerator,
                focusPlannedDateGenerator);
        var tied = taskEntity();
        tied.setTitle("Позвонить в банк");
        when(taskRepository.findFocusTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(List.of(tied));
        when(taskMapper.toResponse(tied)).thenReturn(mockResponse(taskId, "Позвонить в банк"));

        var result = gated.getFocusTasks(userId, null);

        assertThat(result.tasks()).hasSize(1);
        assertThat(tied.getLastShownInFocusAt()).isNotNull();
    }

    @Test
    void getFocusTasks_neverExcludesUntiedTask_regardlessOfHour() {
        var config = new FocusHoursGateConfig();
        config.setRoots(List.of("банк"));
        var gated = new TaskServiceImpl(taskRepository, groupRepository, tagRepository, taskMapper,
                taskReminderService, reminderRepository, recurrenceRepository, auditService, groupStyleResolver,
                clock, focusTaskRanker, userService, config, new FocusHintConfig(), focusHintGenerator,
                focusPlannedDateGenerator);
        var untied = taskEntity();
        untied.setTitle("купить молоко");
        when(taskRepository.findFocusTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(List.of(untied));
        when(taskMapper.toResponse(untied)).thenReturn(mockResponse(taskId, "купить молоко"));

        var result = gated.getFocusTasks(userId, null);

        assertThat(result.tasks()).hasSize(1);
    }

    @Test
    void getUpcomingFocusTasks_doesNotApplyHoursGate() {
        var config = new FocusHoursGateConfig();
        config.setRoots(List.of("банк"));
        var gated = new TaskServiceImpl(taskRepository, groupRepository, tagRepository, taskMapper,
                taskReminderService, reminderRepository, recurrenceRepository, auditService, groupStyleResolver,
                clock, focusTaskRanker, userService, config, new FocusHintConfig(), focusHintGenerator,
                focusPlannedDateGenerator);
        var tied = taskEntity();
        tied.setTitle("Позвонить в банк");
        when(taskRepository.findUpcomingFocusTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(List.of(tied));
        when(taskMapper.toResponse(tied)).thenReturn(mockResponse(taskId, "Позвонить в банк"));

        var result = gated.getUpcomingFocusTasks(userId, null);

        assertThat(result.tasks()).hasSize(1);
    }

    // --- Пункт А: день исполнения не участвует в просрочке ---

    @Test
    void getDigest_taskWithOnlyPastPlannedDate_doesNotCountAsOverdue() {
        var entity = taskEntity();
        entity.setPlannedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(3));
        when(taskRepository.findDigestTasks(eq(userId), any(OffsetDateTime.class)))
                .thenReturn(List.of(entity));
        when(taskMapper.toResponse(entity)).thenReturn(mockResponse(taskId, "задача"));

        var result = taskService.getDigest(userId, java.time.LocalDate.now());

        assertThat(result.overdueTasks()).isZero();
    }

    @Test
    void update_settingPlannedDate_doesNotTouchReminders() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest(null, null, null, null, null, null, null, null, null,
                OffsetDateTime.now().plusDays(1));
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        assertThat(entity.getPlannedDate()).isNotNull();
        assertThat(entity.getDeadline()).isNull();
        verify(taskReminderService, never()).cancelForTask(any());
        verify(taskReminderService, never()).planForDeadline(any(), any());
    }

    // --- Пункт В: сигналы видимого продвижения ---

    @Test
    void update_transitioningToInProgress_setsStartedAt() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest(null, null, null, TaskStatus.IN_PROGRESS, null, null, null, null, null,
                null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        assertThat(entity.getStartedAt()).isNotNull();
    }

    @Test
    void update_settingPlannedDate_setsPlannedDateSetAt() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest(null, null, null, null, null, null, null, null, null,
                OffsetDateTime.now().plusDays(1));
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        assertThat(entity.getPlannedDateSetAt()).isNotNull();
    }

    // --- Блок А3: снять повтор ---

    @Test
    void clearRecurrence_deletesExistingRule() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        var recurrence = recurrenceEntity(RecurrenceType.DAILY, 1, null, null, null);
        when(recurrenceRepository.findById(taskId)).thenReturn(Optional.of(recurrence));

        taskService.clearRecurrence(userId, taskId);

        verify(recurrenceRepository).delete(recurrence);
    }

    @Test
    void clearRecurrence_isNoOpWhenNoneExists() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(recurrenceRepository.findById(taskId)).thenReturn(Optional.empty());

        taskService.clearRecurrence(userId, taskId);

        verify(recurrenceRepository, never()).delete(any());
    }

    @Test
    void clearRecurrence_throwsNotFound_whenTaskMissing() {
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.clearRecurrence(userId, taskId))
                .isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(recurrenceRepository);
    }

    private TaskJpaEntity taskEntity() {
        var e = new TaskJpaEntity();
        e.setUserId(userId);
        e.setTitle("задача");
        return e;
    }

    private TaskResponse mockResponse(UUID id, String title) {
        return new TaskResponse(id, title, null, null, TaskStatus.TODO,
                null, null, null, null, null, List.of(), null, null, null, List.of());
    }

    // --- А1/А3: чтение напоминаний ---

    @Test
    void findById_embedsRemindersFromRepository() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskMapper.toResponse(entity)).thenReturn(mockResponse(taskId, "задача"));
        var reminderEntity = new ReminderJpaEntity();
        when(reminderRepository.findByTaskIdAndStatusOrderByFireAtAsc(taskId, ReminderStatus.PENDING))
                .thenReturn(List.of(reminderEntity));
        var reminderResponse = new ReminderResponse(UUID.randomUUID(), OffsetDateTime.now(), "PENDING");
        when(taskMapper.toReminderResponses(List.of(reminderEntity))).thenReturn(List.of(reminderResponse));

        var result = taskService.findById(userId, taskId);

        assertThat(result.reminders()).containsExactly(reminderResponse);
    }

    @Test
    void findAll_embedsRemindersInOneBatchQuery_notPerTask() {
        var task1 = taskEntity();
        task1.setId(UUID.randomUUID());
        var task2 = taskEntity();
        task2.setId(UUID.randomUUID());
        var pageable = PageRequest.of(0, 20);
        Page<TaskJpaEntity> page = new PageImpl<>(List.of(task1, task2));
        var filter = new TaskFilterRequest(null, null, null, null);

        when(taskRepository.findAllWithFilter(eq(userId), any(), any(), any(), any(), eq(pageable))).thenReturn(page);
        when(taskMapper.toResponse(task1)).thenReturn(mockResponse(task1.getId(), "задача 1"));
        when(taskMapper.toResponse(task2)).thenReturn(mockResponse(task2.getId(), "задача 2"));
        when(reminderRepository.findByTaskIdInAndStatusOrderByFireAtAsc(any(), eq(ReminderStatus.PENDING)))
                .thenReturn(List.of());

        var result = taskService.findAll(userId, filter, pageable);

        assertThat(result.getContent()).hasSize(2);
        verify(reminderRepository, times(1))
                .findByTaskIdInAndStatusOrderByFireAtAsc(any(), eq(ReminderStatus.PENDING));
        verify(reminderRepository, never()).findByTaskIdAndStatusOrderByFireAtAsc(any(), any());
    }

    @Test
    void findAll_taskWithoutReminders_getsEmptyListNotNull() {
        var entity = taskEntity();
        entity.setId(UUID.randomUUID());
        var pageable = PageRequest.of(0, 20);
        Page<TaskJpaEntity> page = new PageImpl<>(List.of(entity));
        var filter = new TaskFilterRequest(null, null, null, null);

        when(taskRepository.findAllWithFilter(eq(userId), any(), any(), any(), any(), eq(pageable))).thenReturn(page);
        when(taskMapper.toResponse(entity)).thenReturn(mockResponse(entity.getId(), "задача"));
        when(reminderRepository.findByTaskIdInAndStatusOrderByFireAtAsc(any(), eq(ReminderStatus.PENDING)))
                .thenReturn(List.of());

        var result = taskService.findAll(userId, filter, pageable);

        assertThat(result.getContent().getFirst().reminders()).isNotNull().isEmpty();
    }

    @Test
    void getReminders_returnsRemindersFromRepository() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        var reminderEntity = new ReminderJpaEntity();
        when(reminderRepository.findByTaskIdAndStatusOrderByFireAtAsc(taskId, ReminderStatus.PENDING))
                .thenReturn(List.of(reminderEntity));
        var reminderResponse = new ReminderResponse(UUID.randomUUID(), OffsetDateTime.now(), "PENDING");
        when(taskMapper.toReminderResponses(List.of(reminderEntity))).thenReturn(List.of(reminderResponse));

        var result = taskService.getReminders(userId, taskId);

        assertThat(result).containsExactly(reminderResponse);
    }

    @Test
    void getReminders_throwsNotFound_whenTaskMissing() {
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.getReminders(userId, taskId))
                .isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(reminderRepository);
    }

    // --- А2: снятие одного напоминания ---

    @Test
    void cancelReminder_delegatesToTaskReminderService_afterOwnershipCheck() {
        var entity = taskEntity();
        UUID reminderId = UUID.randomUUID();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));

        taskService.cancelReminder(userId, taskId, reminderId);

        verify(taskReminderService).cancelReminder(taskId, reminderId);
    }

    @Test
    void cancelReminder_throwsNotFound_whenTaskDoesNotBelongToUser() {
        UUID reminderId = UUID.randomUUID();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.cancelReminder(userId, taskId, reminderId))
                .isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(taskReminderService);
    }
}
