package ru.taskflow.nlp.infrastructure.yandex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.taskflow.nlp.domain.LlmProvider;
import ru.taskflow.nlp.domain.ParsedTask;
import ru.taskflow.nlp.domain.ParsedTasks;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.yandex-gpt.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class YandexGptLlmProvider implements LlmProvider {

    private final YandexGptConfig config;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Override
    public ParsedTasks parseTasksFromText(String text, String userTimezone, String userLanguage) {
        try {
            if (!config.isEnabled() || config.getApiKey() == null || config.getApiKey().isEmpty()) {
                return new ParsedTasks(List.of());
            }
            return callYandexGptApi(text, userTimezone, userLanguage);
        } catch (Exception e) {
            log.warn("Failed to parse tasks from YandexGPT", e);
            return new ParsedTasks(List.of());
        }
    }

    private ParsedTasks callYandexGptApi(String text, String userTimezone, String userLanguage) throws JsonProcessingException {
        String systemPrompt = getSystemPrompt();

        var request = Map.of(
            "modelUri", "gpt://" + config.getFolderId() + "/" + config.getModel(),
            "messages", List.of(
                Map.of("role", "system", "text", systemPrompt),
                Map.of("role", "user", "text", text)
            ),
            "completionOptions", Map.of(
                "stream", false,
                "temperature", 0.1,
                "maxTokens", "2000"
            )
        );

        var response = restClient.post()
            .uri("/completionStream")
            .header("Authorization", "Bearer " + config.getApiKey())
            .header("Content-Type", "application/json")
            .body(request)
            .retrieve()
            .body(YandexGptResponse.class);

        if (response == null || response.result() == null || response.result().alternatives() == null || response.result().alternatives().isEmpty()) {
            return new ParsedTasks(List.of());
        }

        String content = response.result().alternatives().getFirst().message().text();
        return parseJsonResponse(content, userTimezone);
    }

    private ParsedTasks parseJsonResponse(String jsonContent, String userTimezone) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(jsonContent);
        JsonNode tasksNode = root.get("tasks");

        if (tasksNode == null || !tasksNode.isArray()) {
            return new ParsedTasks(List.of());
        }

        List<ParsedTask> tasks = new ArrayList<>();
        ZoneId zoneId = ZoneId.of(userTimezone);

        for (JsonNode taskNode : tasksNode) {
            try {
                String title = taskNode.get("title").asText();
                String description = taskNode.has("description") && !taskNode.get("description").isNull()
                    ? taskNode.get("description").asText()
                    : null;
                String priority = taskNode.has("priority") ? taskNode.get("priority").asText() : "MEDIUM";
                String deadline = taskNode.has("deadline") && !taskNode.get("deadline").isNull()
                    ? taskNode.get("deadline").asText()
                    : null;
                String group = taskNode.has("group") && !taskNode.get("group").isNull()
                    ? taskNode.get("group").asText()
                    : null;

                List<String> tags = new ArrayList<>();
                if (taskNode.has("tags") && taskNode.get("tags").isArray()) {
                    taskNode.get("tags").forEach(tag -> tags.add(tag.asText()));
                }

                String recurrence = taskNode.has("recurrence") ? taskNode.get("recurrence").asText() : "NONE";

                Instant parsedDeadline = deadline != null ? ZonedDateTime.parse(deadline).toInstant() : null;

                tasks.add(new ParsedTask(title, description, priority, parsedDeadline, group, tags, recurrence));
            } catch (Exception e) {
                log.warn("Failed to parse task from YandexGPT response", e);
            }
        }

        return new ParsedTasks(tasks);
    }

    private String getSystemPrompt() {
        return """
            Ты ассистент для разбора задач из текста. Твоя задача — преобразовать пользовательский текст в структурированный JSON с информацией о задачах.

            Всегда отвечай JSON, без дополнительного текста. Структура JSON:
            {
              "tasks": [
                {
                  "title": "название задачи (обязательно)",
                  "description": "описание (опционально)",
                  "priority": "LOW|MEDIUM|HIGH|URGENT (по умолчанию MEDIUM)",
                  "deadline": "ISO 8601 дата (опционально, парсить из текста)",
                  "group": "категория/проект (опционально)",
                  "tags": ["список", "тегов"],
                  "recurrence": "NONE|DAILY|WEEKLY|MONTHLY (по умолчанию NONE)"
                }
              ]
            }

            Правила:
            - Если в тексте упомянуто время (завтра, в 18:00, через 2 часа), вычисли deadline
            - Приоритеты: URGENT для срочных, HIGH для важных, LOW для неспешных
            - Теги — это ключевые слова из текста
            - Повторяющиеся задачи (каждый день, еженедельно) — указывай в recurrence
            """;
    }

    record YandexGptResponse(Result result) {}
    record Result(List<Alternative> alternatives) {}
    record Alternative(Message message) {}
    record Message(String text) {}
}
