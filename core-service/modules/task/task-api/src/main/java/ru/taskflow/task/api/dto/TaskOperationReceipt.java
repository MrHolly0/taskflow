package ru.taskflow.task.api.dto;
import java.time.OffsetDateTime;
import java.util.UUID;
public record TaskOperationReceipt(UUID id,UUID taskId,String kind,OffsetDateTime expiresAt,boolean undone) {}
