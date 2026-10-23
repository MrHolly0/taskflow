package ru.taskflow.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.shared.exception.ValidationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class AssistantRequestStore {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Optional<Proposal> find(UUID userId, String requestKey, String fingerprint) {
        validateKey(requestKey);
        List<?> rows = entityManager.createNativeQuery("""
                SELECT fingerprint, response_json FROM assistant_request_results
                WHERE user_id = :userId AND request_key = :requestKey
                """)
                .setParameter("userId", userId).setParameter("requestKey", requestKey).getResultList();
        return rows.isEmpty() ? Optional.empty() : response((Object[]) rows.getFirst(), fingerprint);
    }

    @Transactional
    public Proposal complete(UUID userId, String requestKey, String fingerprint, Supplier<Proposal> operation) {
        validateKey(requestKey);
        entityManager.createNativeQuery("""
                INSERT INTO assistant_request_results (user_id, request_key, fingerprint)
                VALUES (:userId, :requestKey, :fingerprint)
                ON CONFLICT (user_id, request_key) DO NOTHING
                """)
                .setParameter("userId", userId).setParameter("requestKey", requestKey)
                .setParameter("fingerprint", fingerprint).executeUpdate();
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                SELECT fingerprint, response_json FROM assistant_request_results
                WHERE user_id = :userId AND request_key = :requestKey FOR UPDATE
                """)
                .setParameter("userId", userId).setParameter("requestKey", requestKey).getSingleResult();
        Optional<Proposal> existing = response(row, fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }
        Proposal proposal = operation.get();
        try {
            entityManager.createNativeQuery("""
                    UPDATE assistant_request_results SET response_json = :response
                    WHERE user_id = :userId AND request_key = :requestKey
                    """)
                    .setParameter("response", objectMapper.writeValueAsString(proposal))
                    .setParameter("userId", userId).setParameter("requestKey", requestKey).executeUpdate();
            return proposal;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сохранить результат обращения", exception);
        }
    }

    private Optional<Proposal> response(Object[] row, String fingerprint) {
        if (!fingerprint.equals(row[0])) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ключ повторного запроса уже использован с другими данными");
        }
        if (row[1] == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readerFor(Proposal.class)
                        .without(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                        .readValue((String) row[1]));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось прочитать результат обращения", exception);
        }
    }

    private void validateKey(String requestKey) {
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ValidationException("Некорректный ключ повторного запроса");
        }
    }
}
