package ru.taskflow.nlp.infrastructure.web.dto;

import java.util.List;

public record ParseTextRequest(
    String text,
    String userTimezone,
    String userLanguage,
    List<String> existingGroups
) {
}
