package ru.taskflow.notificationworker.application;

import java.util.UUID;

public record EngagementPayload(UUID promptId, UUID taskId, String taskTitle, String deadline,
                                String timezone, String appUrl) {}
