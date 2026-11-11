package ru.taskflow.task.api.dto;
import java.util.UUID;
public record TaskUndoResult(UUID taskId,String kind,boolean undone) {}
