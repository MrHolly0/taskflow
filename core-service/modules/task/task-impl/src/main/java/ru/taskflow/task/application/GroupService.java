package ru.taskflow.task.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.task.api.dto.CreateGroupRequest;
import ru.taskflow.task.api.dto.GroupResponse;
import ru.taskflow.task.api.exception.GroupNotFoundException;
import ru.taskflow.task.infrastructure.persistence.GroupJpaEntity;
import ru.taskflow.task.infrastructure.persistence.GroupRepository;

import java.util.List;
import java.util.UUID;

/**
 * Сервис управления группами задач.
 *
 * Позволяет пользователям организовывать задачи в группы (проекты, категории).
 * Группы содержат метаданные для визуализации (цвет, иконка).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupService {

    private final GroupRepository groupRepository;

    /**
     * Получает все группы пользователя.
     *
     * @param userId ID пользователя
     * @return список всех групп с метаданными
     */
    public List<GroupResponse> findAll(UUID userId) {
        return groupRepository.findAllByUserId(userId)
                .stream()
                .map(g -> new GroupResponse(g.getId(), g.getName(), g.getColor(), g.getIcon()))
                .toList();
    }

    /**
     * Создаёт новую группу для пользователя.
     *
     * @param userId ID пользователя
     * @param request параметры группы (название, цвет, иконка)
     * @return созданная группа
     */
    @Transactional
    public GroupResponse create(UUID userId, CreateGroupRequest request) {
        var g = new GroupJpaEntity();
        g.setUserId(userId);
        g.setName(request.name());
        g.setColor(request.color());
        g.setIcon(request.icon());
        GroupJpaEntity saved = groupRepository.save(g);
        return new GroupResponse(saved.getId(), saved.getName(), saved.getColor(), saved.getIcon());
    }

    /**
     * Удаляет группу пользователя.
     *
     * @param userId ID пользователя
     * @param groupId ID группы для удаления
     * @throws GroupNotFoundException если группа не найдена
     */
    @Transactional
    public void delete(UUID userId, UUID groupId) {
        var group = groupRepository.findByIdAndUserId(groupId, userId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        groupRepository.delete(group);
    }
}
