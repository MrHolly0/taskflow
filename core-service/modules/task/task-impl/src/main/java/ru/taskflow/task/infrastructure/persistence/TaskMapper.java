package ru.taskflow.task.infrastructure.persistence;

import org.mapstruct.*;
import ru.taskflow.task.api.dto.TaskResponse;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface TaskMapper {

    @Mapping(target = "groupId", source = "group.id")
    @Mapping(target = "groupName", source = "group.name")
    @Mapping(target = "tags", expression = "java(tagNames(entity))")
    TaskResponse toResponse(TaskJpaEntity entity);

    default List<String> tagNames(TaskJpaEntity entity) {
        return entity.getTags().stream().map(TagJpaEntity::getName).toList();
    }
}
