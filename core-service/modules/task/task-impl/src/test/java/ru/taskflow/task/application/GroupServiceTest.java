package ru.taskflow.task.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.task.api.dto.CreateGroupRequest;
import ru.taskflow.task.api.exception.GroupNotFoundException;
import ru.taskflow.task.infrastructure.persistence.GroupJpaEntity;
import ru.taskflow.task.infrastructure.persistence.GroupRepository;
import ru.taskflow.task.infrastructure.persistence.TaskRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock private GroupRepository groupRepository;
    @Mock private TaskRepository taskRepository;

    private GroupService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GroupService(groupRepository, taskRepository);
    }

    @Test
    void updatesStyleWithoutChangingOwnerOrTaskLinks() {
        var createdAt = OffsetDateTime.parse("2026-08-01T12:00:00Z");
        var group = new GroupJpaEntity();
        group.setId(groupId);
        group.setUserId(userId);
        group.setName("Личное");
        group.setColor("#112233");
        group.setIcon("folder");
        group.setSortOrder(7);
        group.setCreatedAt(createdAt);
        when(groupRepository.findByIdAndUserId(groupId, userId)).thenReturn(Optional.of(group));

        var response = service.update(userId, groupId,
                new CreateGroupRequest("  Учёба  ", "#445566", "school"));

        assertThat(response.id()).isEqualTo(groupId);
        assertThat(response.name()).isEqualTo("Учёба");
        assertThat(response.color()).isEqualTo("#445566");
        assertThat(response.icon()).isEqualTo("school");
        assertThat(group.getName()).isEqualTo(response.name());
        assertThat(group.getColor()).isEqualTo(response.color());
        assertThat(group.getIcon()).isEqualTo(response.icon());
        assertThat(group.getUserId()).isEqualTo(userId);
        assertThat(group.getSortOrder()).isEqualTo(7);
        assertThat(group.getCreatedAt()).isEqualTo(createdAt);
        verify(groupRepository).findByIdAndUserId(groupId, userId);
        verifyNoMoreInteractions(groupRepository);
        verifyNoInteractions(taskRepository);
    }

    @Test
    void rejectsGroupOwnedByAnotherUser() {
        var otherUserId = UUID.randomUUID();
        when(groupRepository.findByIdAndUserId(groupId, otherUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(otherUserId, groupId,
                new CreateGroupRequest("Чужая группа", "#445566", "school")))
                .isInstanceOf(GroupNotFoundException.class);

        verify(groupRepository).findByIdAndUserId(groupId, otherUserId);
        verifyNoMoreInteractions(groupRepository);
        verifyNoInteractions(taskRepository);
    }

    @Test
    void rejectsMissingGroup() {
        when(groupRepository.findByIdAndUserId(groupId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(userId, groupId,
                new CreateGroupRequest("Учёба", null, null)))
                .isInstanceOf(GroupNotFoundException.class);

        verify(groupRepository).findByIdAndUserId(groupId, userId);
        verifyNoMoreInteractions(groupRepository);
        verifyNoInteractions(taskRepository);
    }
}
