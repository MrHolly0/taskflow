package ru.taskflow.shared.widgets;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WidgetState(String kind, String key, long revision, String title, List<List<String>> rows,
                          boolean terminal, String heading, String summary, String status, String note,
                          Integer page, Integer total, Integer selected, Boolean accepted, String warning, String fingerprint) {
    public WidgetState(String kind, String title, List<List<String>> rows) {
        this(kind, null, 0, title, rows, false, null, null, null, null, null, null, null, null, null, null);
    }
    public WidgetState fingerprint(String value) {
        return new WidgetState(kind,key,revision,title,rows,terminal,heading,summary,status,note,page,total,selected,accepted,warning,value);
    }
    public WidgetState identity(UUID id, long version) {
        return new WidgetState(kind, "w:" + id + ":" + version, version, title, rows, terminal,
                heading, summary, status, note, page, total, selected, accepted, warning, fingerprint);
    }
    public WidgetState finish(String heading, String message) {
        return new WidgetState(kind, key, revision, title, rows, true, heading, null, message, "",
                null, null, null, null, warning, fingerprint);
    }
    public WidgetState proposal(int page, int total, int selected, boolean accepted, String warning) {
        return new WidgetState(kind, key, revision, title, rows, terminal, heading, summary, status, note,
                page, total, selected, accepted, warning, fingerprint);
    }
}
