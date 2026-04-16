package ru.taskflow.task.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
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
        var request = new UpdateTaskRequest("новый заголовок", null, null, null, null, null, null, null);
        var response = mockResponse(taskId, "новый заголовок");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.update(userId, taskId, request);

        assertThat(entity.getTitle()).isEqualTo("новый заголовок");
        assertThat(result.title()).isEqualTo("новый заголовок");
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
    void delete_marksAsDeleted() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));

        taskService.delete(userId, taskId);

        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getDeletedAt()).isNotNull();
    }

    @Test
    void delete_throwsNotFound_whenMissing() {
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.delete(userId, taskId))
                .isInstanceOf(TaskNotFoundException.class);
    }

    private TaskJpaEntity taskEntity() {
        var e = new TaskJpaEntity();
        e.setUserId(userId);
        e.setTitle("задача");
        return e;
    }

    private TaskResponse mockResponse(UUID id, String title) {
        return new TaskResponse(id, title, null, null, TaskStatus.TODO,
                null, null, false, null, null, null, List.of(), null, null, null);
    }
}
