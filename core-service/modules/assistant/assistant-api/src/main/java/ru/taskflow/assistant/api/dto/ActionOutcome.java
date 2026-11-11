package ru.taskflow.assistant.api.dto;
import java.util.UUID;
import java.time.OffsetDateTime;
public record ActionOutcome(int ordinal,String summary,boolean success,String error,UUID taskId,
                            UUID undoId,OffsetDateTime undoExpiresAt,boolean undone) {
    public ActionOutcome(int ordinal,String summary,boolean success,String error,UUID taskId) {
        this(ordinal,summary,success,error,taskId,null,null,false);
    }
    public ActionOutcome(int ordinal,String summary,boolean success,String error) { this(ordinal,summary,success,error,null); }
}
