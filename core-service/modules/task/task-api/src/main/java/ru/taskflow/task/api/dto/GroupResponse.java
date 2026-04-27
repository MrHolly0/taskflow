package ru.taskflow.task.api.dto;

import java.util.UUID;

public record GroupResponse(UUID id, String name, String color, String icon) {}
