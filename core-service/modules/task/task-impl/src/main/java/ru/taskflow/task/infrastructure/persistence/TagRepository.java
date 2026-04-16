package ru.taskflow.task.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagRepository extends JpaRepository<TagJpaEntity, UUID> {

    Optional<TagJpaEntity> findByUserIdAndName(UUID userId, String name);

    List<TagJpaEntity> findAllByUserIdAndNameIn(UUID userId, List<String> names);
}
