package ru.taskflow.shared.widgets;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WidgetState(String kind, String key, long revision, String title, List<List<String>> rows,
                          boolean terminal, String heading, String summary, String status, String note,
                          Integer page, Integer total, Integer selected, Boolean accepted, String warning, String fingerprint,String taskId,Boolean undoAvailable) {
    public WidgetState(String kind, String title, List<List<String>> rows) {
        this(kind, null, 0, title, rows, false, null, null, null, null, null, null, null, null, null, null,null,null);
    }
    public WidgetState feedback(String message) {
        return new WidgetState(kind,key,revision,title,rows,terminal,heading,summary,status,note,page,total,selected,accepted,message,fingerprint,taskId,undoAvailable);
    }
    public WidgetState checkins(int page,int total,String taskId,String status) {
        return new WidgetState(kind,key,revision,title,rows,false,null,null,status,"",page,total,null,null,null,null,taskId,null);
    }
    public WidgetState receipt(int page,int total,boolean available,String taskId,String heading,String status) {
        return new WidgetState(kind,key,revision,title,rows,false,heading,null,status,"",page,total,null,null,null,null,taskId,available);
    }
    public WidgetState fingerprint(String value) {
        return new WidgetState(kind,key,revision,title,rows,terminal,heading,summary,status,note,page,total,selected,accepted,warning,value,taskId,undoAvailable);
    }
    public WidgetState identity(UUID id, long version) {
        return new WidgetState(kind, "w:" + id + ":" + version, version, title, rows, terminal,
                heading, summary, status, note, page, total, selected, accepted, warning, fingerprint,taskId,undoAvailable);
    }
    public WidgetState finish(String heading, String message) {
        return new WidgetState(kind, key, revision, title, rows, true, heading, null, message, "",
                null, null, null, null, warning, fingerprint,taskId,undoAvailable);
    }
    public WidgetState proposal(int page, int total, int selected, boolean accepted, String warning) {
        return new WidgetState(kind, key, revision, title, rows, terminal, heading, summary, status, note,
                page, total, selected, accepted, warning, fingerprint,taskId,undoAvailable);
    }
}
