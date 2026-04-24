package ru.taskflow.nlp.infrastructure.web.dto;

public record ParseTextRequest(
    String text,
    String userTimezone,
    String userLanguage
) {
}
