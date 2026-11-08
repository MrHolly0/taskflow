package ru.taskflow.task.api.dto;

import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;
import java.time.OffsetDateTime;

public record AssistantTaskSelector(String query, String group, TaskStatus status, TaskPriority priority,
                                    boolean overdue, OffsetDateTime deadlineFrom, OffsetDateTime deadlineBefore,
                                    boolean all) {}
