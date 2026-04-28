package ru.taskflow.nlp.infrastructure.groq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
@Slf4j
public class GroqLlmProvider implements LlmProvider {

    private final GroqConfig config;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Override
    public ParsedTasks parseTasksFromText(String text, String userTimezone, String userLanguage) {
        try {
            return callGroqApi(text, userTimezone, userLanguage);
        } catch (Exception e) {
            log.error("Failed to parse tasks from Groq", e);
            return new ParsedTasks(List.of());
        }
    }

    private ParsedTasks callGroqApi(String text, String userTimezone, String userLanguage) throws JsonProcessingException {
        String systemPrompt = getSystemPrompt();

        var request = Map.of(
            "model", config.getLlmModel(),
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", text)
            ),
            "response_format", Map.of("type", "json_object"),
            "temperature", 0.1
        );

        var response = restClient.post()
            .uri("/chat/completions")
            .header("Authorization", "Bearer " + config.getApiKey())
            .body(request)
            .retrieve()
            .body(GroqCompletionResponse.class);

        if (response == null || response.choices().isEmpty()) {
            return new ParsedTasks(List.of());
        }

        String content = response.choices().getFirst().message().content();
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

                Instant deadlineInstant = deadline != null ? parseDeadline(deadline, zoneId) : null;

                tasks.add(new ParsedTask(
                    title,
                    description,
                    priority,
                    deadlineInstant,
                    group,
                    tags,
                    recurrence
                ));
            } catch (Exception e) {
                log.warn("Failed to parse task from JSON node: {}", taskNode, e);
            }
        }

        return new ParsedTasks(tasks);
    }

    private Instant parseDeadline(String deadline, ZoneId zoneId) {
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(deadline, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return zdt.toInstant();
        } catch (Exception e) {
            try {
                ZonedDateTime zdt = ZonedDateTime.parse(deadline + "T12:00:00+03:00", DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                return zdt.toInstant();
            } catch (Exception ex) {
                log.warn("Failed to parse deadline: {}", deadline, ex);
                return null;
            }
        }
    }

    private String getSystemPrompt() {
        return """
            Ты — помощник для разбора задач на русском языке.
            Пользователь описывает задачи в виде текста (часто списком или потоком сознания).

            Твоя задача: распарсить текст и вернуть JSON со списком структурированных задач.

            Формат ответа (JSON):
            {
              "tasks": [
                {
                  "title": "название задачи (обязательно)",
                  "description": "описание или null",
                  "priority": "LOW|MEDIUM|HIGH|URGENT (по умолчанию MEDIUM)",
                  "deadline": "ISO-8601 datetime или null",
                  "group": "категория задачи на русском — ОБЯЗАТЕЛЬНО заполни одним словом, например: Покупки, Работа, Здоровье, Дом, Учёба, Личное, Финансы, Спорт, Семья — выбери наиболее подходящую или придумай короткое название",
                  "tags": ["массив строк"],
                  "recurrence": "NONE|DAILY|WEEKLY|MONTHLY"
                }
              ]
            }

            Примеры:
            Входной текст: "завтра до 18 купить молоко и хлеб, сходить в аптеку за витаминами"
            {
              "tasks": [
                {
                  "title": "Купить молоко и хлеб",
                  "priority": "MEDIUM",
                  "deadline": "2026-04-25T18:00:00+03:00",
                  "group": "Покупки",
                  "tags": ["магазин"],
                  "recurrence": "NONE"
                },
                {
                  "title": "Сходить в аптеку за витаминами",
                  "priority": "MEDIUM",
                  "deadline": null,
                  "group": "Здоровье",
                  "tags": ["аптека"],
                  "recurrence": "NONE"
                }
              ]
            }

            Входной текст: "каждый день в 9 утра делать зарядку, сдать отчёт начальнику до пятницы"
            {
              "tasks": [
                {
                  "title": "Делать зарядку",
                  "priority": "MEDIUM",
                  "deadline": "2026-04-25T09:00:00+03:00",
                  "group": "Спорт",
                  "tags": ["здоровье"],
                  "recurrence": "DAILY"
                },
                {
                  "title": "Сдать отчёт начальнику",
                  "priority": "HIGH",
                  "deadline": "2026-04-25T18:00:00+03:00",
                  "group": "Работа",
                  "tags": ["отчёт"],
                  "recurrence": "NONE"
                }
              ]
            }

            Правила:
            - Всегда парси в "tasks" список, даже если одна задача
            - Deadline в ISO-8601 с timezone +03:00 (Москва)
            - Если дата не указана явно (только время), используй сегодняшнюю дату
            - Priority: LOW (обычное дело), MEDIUM (стандартное), HIGH (важное), URGENT (очень срочное)
            - group — ВСЕГДА заполняй, никогда не null. Одно короткое слово или два на русском
            - Tags — бери из контекста (покупки, работа, здоровье и т.п.)
            - Recurrence — определяй из фраз типа "каждый день", "по вторникам", "еженедельно"
            - Если text слишком расплывчато — создавай задачу с тем, что понял, но без выдумок
            """;
    }

    record GroqCompletionResponse(
        List<Choice> choices
    ) {
    }

    record Choice(
        Message message
    ) {
    }

    record Message(
        String content
    ) {
    }
}
