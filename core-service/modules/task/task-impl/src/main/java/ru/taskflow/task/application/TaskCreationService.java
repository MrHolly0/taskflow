package ru.taskflow.task.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.shared.exception.ValidationException;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskCreationService {

    private final TaskService taskService;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public TaskResponse create(UUID userId, CreateTaskRequest request, String requestKey, boolean quick) {
        if (requestKey == null) {
            return quick ? taskService.createQuick(userId, request) : taskService.create(userId, request);
        }
        if (!requestKey.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ValidationException("Некорректный ключ повторного запроса");
        }
        String fingerprint = fingerprint(request, quick);
        entityManager.createNativeQuery("""
                INSERT INTO task_creation_requests (user_id, request_key, fingerprint)
                VALUES (:userId, :requestKey, :fingerprint)
                ON CONFLICT (user_id, request_key) DO NOTHING
                """)
                .setParameter("userId", userId).setParameter("requestKey", requestKey)
                .setParameter("fingerprint", fingerprint).executeUpdate();
        Object[] stored = (Object[]) entityManager.createNativeQuery("""
                SELECT fingerprint, response_json FROM task_creation_requests
                WHERE user_id = :userId AND request_key = :requestKey FOR UPDATE
                """)
                .setParameter("userId", userId).setParameter("requestKey", requestKey).getSingleResult();
        if (!fingerprint.equals(stored[0])) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ключ повторного запроса уже использован с другими данными");
        }
        try {
            if (stored[1] != null) {
                return objectMapper.readerFor(TaskResponse.class)
                        .without(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                        .readValue((String) stored[1]);
            }
            TaskResponse response = quick ? taskService.createQuick(userId, request) : taskService.create(userId, request);
            entityManager.createNativeQuery("""
                    UPDATE task_creation_requests SET response_json = :response
                    WHERE user_id = :userId AND request_key = :requestKey
                    """)
                    .setParameter("response", objectMapper.writeValueAsString(response))
                    .setParameter("userId", userId).setParameter("requestKey", requestKey).executeUpdate();
            return response;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сохранить результат создания задачи", exception);
        }
    }

    private String fingerprint(CreateTaskRequest request, boolean quick) {
        try {
            byte[] bytes = (quick + ":" + objectMapper.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Не удалось проверить повторный запрос", exception);
        }
    }
}
