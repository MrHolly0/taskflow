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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupService {

    private final GroupRepository groupRepository;

    public List<GroupResponse> findAll(UUID userId) {
        return groupRepository.findAllByUserId(userId)
                .stream()
                .map(g -> new GroupResponse(g.getId(), g.getName(), g.getColor(), g.getIcon()))
                .toList();
    }

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

    @Transactional
    public void delete(UUID userId, UUID groupId) {
        var group = groupRepository.findByIdAndUserId(groupId, userId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        groupRepository.delete(group);
    }
}
