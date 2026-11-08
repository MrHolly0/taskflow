package ru.taskflow.task.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<TaskJpaEntity, UUID> {

    @Query(value = """
            SELECT t.id FROM tasks t LEFT JOIN groups g ON g.id = t.group_id
            WHERE t.user_id = :userId AND t.is_deleted = false
              AND ((CAST(:status AS varchar) IS NULL AND t.status IN ('TODO','IN_PROGRESS')) OR t.status = :status)
              AND (CAST(:query AS varchar) IS NULL OR LOWER(t.title) LIKE LOWER(:query) ESCAPE '!'
                   OR LOWER(COALESCE(t.description,'')) LIKE LOWER(:query) ESCAPE '!')
              AND (CAST(:groupName AS varchar) IS NULL OR LOWER(g.name) = LOWER(:groupName))
              AND (CAST(:priority AS varchar) IS NULL OR t.priority = :priority)
              AND (:overdue = false OR t.deadline < :now)
              AND (CAST(:from AS timestamptz) IS NULL OR t.deadline >= :from)
              AND (CAST(:before AS timestamptz) IS NULL OR t.deadline < :before)
            ORDER BY t.created_at, t.id LIMIT 21
            """, nativeQuery = true)
    List<UUID> selectAssistantIds(@Param("userId") UUID userId, @Param("query") String query,
            @Param("groupName") String groupName, @Param("status") String status, @Param("priority") String priority,
            @Param("overdue") boolean overdue, @Param("now") OffsetDateTime now,
            @Param("from") OffsetDateTime from, @Param("before") OffsetDateTime before);

    interface AssistantSearchHit {
        UUID getId();
        int getMatchTier();
    }

    @Query(value = """
            WITH terms AS (
                SELECT CASE
                    WHEN lexeme IN ('провер','проверк') THEN to_tsquery('simple','провер:*')
                    WHEN lexeme IN ('подготов','подготовк') THEN to_tsquery('simple','подготов:*')
                    WHEN lexeme IN ('переда','передач') THEN to_tsquery('simple','переда:*')
                    WHEN lexeme IN ('заказ','заказа') THEN to_tsquery('simple','заказ:*')
                    ELSE plainto_tsquery('simple',lexeme) END AS term
                FROM unnest(tsvector_to_array(to_tsvector('russian',COALESCE(CAST(:wordQuery AS text),'')))) tokens(lexeme)
            ), q AS (
                SELECT string_agg('(' || term::text || ')',' & ')::tsquery AS all_terms,
                       string_agg('(' || term::text || ')',' | ')::tsquery AS any_terms
                FROM terms WHERE term::text <> ''
            )
            SELECT t.id AS id,
                CASE WHEN CAST(:query AS varchar) IS NULL OR LOWER(t.title) LIKE LOWER(:query) ESCAPE '!'
                        OR LOWER(COALESCE(t.description,'')) LIKE LOWER(:query) ESCAPE '!' THEN 0
                     WHEN to_tsvector('russian',COALESCE(t.title,'') || ' ' || COALESCE(t.description,'')) @@ q.all_terms THEN 1
                     ELSE 2 END AS "matchTier"
            FROM tasks t LEFT JOIN groups g ON g.id = t.group_id CROSS JOIN q
            WHERE t.user_id = :userId AND t.is_deleted = false
              AND ((CAST(:status AS varchar) IS NULL AND t.status IN ('TODO','IN_PROGRESS')) OR t.status = :status)
              AND (CAST(:query AS varchar) IS NULL OR LOWER(t.title) LIKE LOWER(:query) ESCAPE '!'
                   OR LOWER(COALESCE(t.description,'')) LIKE LOWER(:query) ESCAPE '!'
                   OR to_tsvector('russian',COALESCE(t.title,'') || ' ' || COALESCE(t.description,'')) @@ q.all_terms
                   OR to_tsvector('russian',COALESCE(t.title,'') || ' ' || COALESCE(t.description,'')) @@ q.any_terms)
              AND (CAST(:groupName AS varchar) IS NULL OR LOWER(g.name) = LOWER(:groupName))
              AND (CAST(:priority AS varchar) IS NULL OR t.priority = :priority)
              AND (:overdue = false OR t.deadline < :now)
              AND (CAST(:from AS timestamptz) IS NULL OR t.deadline >= :from)
              AND (CAST(:before AS timestamptz) IS NULL OR t.deadline < :before)
            ORDER BY "matchTier", ts_rank(to_tsvector('russian',COALESCE(t.title,'') || ' ' || COALESCE(t.description,'')),q.any_terms) DESC,
                     t.created_at,t.id LIMIT 21
            """,nativeQuery = true)
    List<AssistantSearchHit> resolveAssistantIds(@Param("userId") UUID userId,@Param("query") String query,
            @Param("wordQuery") String wordQuery,@Param("groupName") String groupName,@Param("status") String status,
            @Param("priority") String priority,@Param("overdue") boolean overdue,@Param("now") OffsetDateTime now,
            @Param("from") OffsetDateTime from,@Param("before") OffsetDateTime before);

    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId AND t.isDeleted = false AND t.id IN :ids
            """)
    List<TaskJpaEntity> findSelectedForAssistant(@Param("userId") UUID userId, @Param("ids") List<UUID> ids);

    Optional<TaskJpaEntity> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TaskJpaEntity t WHERE t.id = :id AND t.userId = :userId")
    Optional<TaskJpaEntity> findForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Modifying
    @Query("""
            UPDATE TaskJpaEntity t SET t.lastShownInFocusAt = :shownAt
            WHERE t.userId = :userId AND t.id IN :ids AND t.isDeleted = false
              AND t.status IN (ru.taskflow.task.api.TaskStatus.TODO, ru.taskflow.task.api.TaskStatus.IN_PROGRESS)
            """)
    int markShownInFocus(@Param("userId") UUID userId, @Param("ids") List<UUID> ids,
                         @Param("shownAt") OffsetDateTime shownAt);

    /**
     * LEFT JOIN FETCH коллекции (tags) не даёт Hibernate резать запрос
     * постранично в SQL (HHH90003004) — он вытягивает весь отфильтрованный
     * набор в память и режет его там. Страница идентификаторов ниже не
     * выбирает коллекции, поэтому пагинация honest: реальный LIMIT/OFFSET
     * в SQL, а не отсечение в Java после полной выборки.
     */
    @Query("""
            SELECT t.id FROM TaskJpaEntity t
            WHERE t.userId = :userId
              AND (:groupId IS NULL OR t.group.id = :groupId)
              AND (:status IS NULL OR t.status = :status)
              AND (:priority IS NULL OR t.priority = :priority)
              AND (:tag IS NULL OR EXISTS (
                  SELECT 1 FROM t.tags tag WHERE tag.name = :tag
              ))
            """)
    Page<UUID> findIdsWithFilter(
            @Param("userId") UUID userId,
            @Param("groupId") UUID groupId,
            @Param("status") TaskStatus status,
            @Param("priority") TaskPriority priority,
            @Param("tag") String tag,
            Pageable pageable
    );

    // Второй запрос страницы: коллекции по уже отобранным id — здесь
    // разбиения на страницы нет, поэтому JOIN FETCH ничему не мешает.
    // Порядок ответа СУБД для IN не гарантирован — сортировка
    // восстанавливается вызывающей стороной по порядку списка ids.
    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group
            LEFT JOIN FETCH t.tags
            WHERE t.id IN :ids
            """)
    List<TaskJpaEntity> findAllByIdInWithCollections(@Param("ids") List<UUID> ids);

    /**
     * Заменяет прежний однозапросный вариант (Page[TaskJpaEntity] одним
     * JOIN FETCH-запросом) — см. javadoc у findIdsWithFilter. Публичная
     * сигнатура и поведение (страница задач с группой и метками,
     * порядок сортировки сохранён) не изменились, изменился только
     * способ выборки внутри.
     */
    default Page<TaskJpaEntity> findAllWithFilter(UUID userId, UUID groupId, TaskStatus status,
                                                   TaskPriority priority, String tag, Pageable pageable) {
        Page<UUID> idPage = findIdsWithFilter(userId, groupId, status, priority, tag, pageable);
        if (idPage.isEmpty()) {
            return Page.empty(pageable);
        }
        Map<UUID, TaskJpaEntity> byId = new LinkedHashMap<>();
        findAllByIdInWithCollections(idPage.getContent()).forEach(t -> byId.put(t.getId(), t));
        List<TaskJpaEntity> ordered = idPage.getContent().stream().map(byId::get).toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }

    // Без ORDER BY намеренно (блок А, ритм пересмотра бессрочных задач) —
    // порядок среди этих кандидатов считает FocusTaskRanker в Java: только
    // там доступна давность показа (lastShownInFocusAt) с осмысленным
    // взвешиванием, а не то, что можно было бы выразить один CASE в JPQL.
    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId
              AND t.status IN (ru.taskflow.task.api.TaskStatus.TODO, ru.taskflow.task.api.TaskStatus.IN_PROGRESS)
              AND t.isDeleted = false
              AND (
                (t.plannedDate IS NULL AND (t.deadline IS NULL OR t.deadline <= :endOfToday))
                OR (t.plannedDate IS NOT NULL AND t.plannedDate <= :endOfToday)
              )
            """)
    List<TaskJpaEntity> findFocusTasks(
            @Param("userId") UUID userId,
            @Param("endOfToday") OffsetDateTime endOfToday
    );

    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId
              AND t.status IN (ru.taskflow.task.api.TaskStatus.TODO, ru.taskflow.task.api.TaskStatus.IN_PROGRESS)
              AND t.isDeleted = false
              AND t.deadline > :endOfToday
            ORDER BY CASE t.priority
              WHEN 'URGENT' THEN 0
              WHEN 'HIGH' THEN 1
              WHEN 'MEDIUM' THEN 2
              ELSE 3
            END,
            t.deadline
            """)
    List<TaskJpaEntity> findUpcomingFocusTasks(
            @Param("userId") UUID userId,
            @Param("endOfToday") OffsetDateTime endOfToday
    );

    // Bulk UPDATE обходит @SQLRestriction("is_deleted = false") — это нужное
    // поведение здесь, переносим и мягко удалённые задачи тоже, а не только видимые.
    @Modifying
    @Query("UPDATE TaskJpaEntity t SET t.userId = :to WHERE t.userId = :from")
    int reassignOwner(@Param("from") UUID from, @Param("to") UUID to);

    // Обычный derived-запрос, не bulk — @SQLRestriction применяется, мягко
    // удалённые не считаются. Это предпросмотр для диалога согласия на
    // слияние, ему нужно видимое пользователю число, а не техническое.
    long countByUserId(UUID userId);

    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId
              AND t.status IN (ru.taskflow.task.api.TaskStatus.TODO, ru.taskflow.task.api.TaskStatus.IN_PROGRESS)
              AND t.isDeleted = false
              AND (DATE(t.deadline) = DATE(:date) OR (t.deadline IS NULL))
            ORDER BY CASE t.priority
              WHEN 'URGENT' THEN 0
              WHEN 'HIGH' THEN 1
              WHEN 'MEDIUM' THEN 2
              ELSE 3
            END,
            t.createdAt DESC
            """)
    List<TaskJpaEntity> findDigestTasks(
            @Param("userId") UUID userId,
            @Param("date") OffsetDateTime date
    );

    @Query(value = """
            SELECT created_at, completed_at, deadline, status
            FROM tasks
            WHERE user_id = :userId
              AND (
                (is_deleted = false AND created_at >= :from)
                OR (completed_at IS NOT NULL AND completed_at >= :from)
                OR (
                  is_deleted = false
                  AND deadline IS NOT NULL
                  AND deadline < :now
                  AND status NOT IN ('DONE', 'CANCELLED')
                )
              )
            """, nativeQuery = true)
    List<Object[]> findStatsRows(
            @Param("userId") UUID userId,
            @Param("from") OffsetDateTime from,
            @Param("now") OffsetDateTime now
    );

    @Query("""
            SELECT t FROM TaskJpaEntity t
            WHERE t.userId = :userId
              AND t.status IN (ru.taskflow.task.api.TaskStatus.TODO, ru.taskflow.task.api.TaskStatus.IN_PROGRESS)
            ORDER BY
              CASE WHEN t.deadline IS NOT NULL AND t.deadline < :now THEN 0 ELSE 1 END ASC,
              CASE WHEN t.deadline IS NULL THEN 1 ELSE 0 END ASC,
              t.deadline ASC,
              t.createdAt DESC
            """)
    List<TaskJpaEntity> findAssistantContext(@Param("userId") UUID userId,
                                             @Param("now") OffsetDateTime now,
                                             Pageable pageable);

    // Тот же изъян, что и у findAllWithFilter (JOIN FETCH коллекции ломает
    // постраничное LIMIT в SQL) — здесь запрос капается сверху (SEARCH_LIMIT
    // в TaskServiceImpl), но при широком LIKE-запросе у пользователя с
    // большим числом задач Hibernate так же вытянул бы в память всё
    // совпавшее по LIKE, прежде чем обрезать до capped в Java.
    @Query("""
            SELECT t.id FROM TaskJpaEntity t
            WHERE t.userId = :userId
              AND t.isDeleted = false
              AND (:includeCompleted = true OR t.status NOT IN (ru.taskflow.task.api.TaskStatus.DONE, ru.taskflow.task.api.TaskStatus.CANCELLED))
              AND (LOWER(t.title) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(t.description) LIKE LOWER(CONCAT('%', :query, '%')))
            ORDER BY t.updatedAt DESC
            """)
    List<UUID> searchIds(
            @Param("userId") UUID userId,
            @Param("query") String query,
            @Param("includeCompleted") boolean includeCompleted,
            Pageable pageable
    );

    default List<TaskJpaEntity> search(UUID userId, String query, boolean includeCompleted, Pageable pageable) {
        List<UUID> ids = searchIds(userId, query, includeCompleted, pageable);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, TaskJpaEntity> byId = new LinkedHashMap<>();
        findAllByIdInWithCollections(ids).forEach(t -> byId.put(t.getId(), t));
        return ids.stream().map(byId::get).toList();
    }

    @Modifying
    @Query(value = """
            UPDATE tasks
            SET is_deleted = true, deleted_at = NOW()
            WHERE user_id = :userId
              AND status IN ('DONE', 'CANCELLED')
              AND is_deleted = false
            """, nativeQuery = true)
    int softDeleteAllCompletedByUser(@Param("userId") UUID userId);

    @Modifying
    @Query(value = """
            UPDATE tasks
            SET is_deleted = true, deleted_at = NOW()
            WHERE user_id = :userId
              AND status IN ('DONE', 'CANCELLED')
              AND is_deleted = false
              AND completed_at < :before
            """, nativeQuery = true)
    int softDeleteCompletedBefore(@Param("userId") UUID userId, @Param("before") OffsetDateTime before);

    @Modifying
    @Query(value = """
            DELETE FROM tasks
            WHERE status IN ('DONE', 'CANCELLED')
              AND completed_at < :before
            """, nativeQuery = true)
    int physicalDeleteCompletedBefore(@Param("before") OffsetDateTime before);

    @Modifying
    @Query(value = "UPDATE tasks SET group_id = NULL WHERE group_id = :groupId", nativeQuery = true)
    void detachGroup(@Param("groupId") UUID groupId);

}
