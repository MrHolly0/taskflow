package ru.taskflow.shared.widgets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.zip.Deflater;

public final class WidgetTemplate {
    private static final String HTML = load();
    private static final ObjectMapper DESCRIPTION_MAPPER = new ObjectMapper();
    private WidgetTemplate() {}

    public static String html() { return HTML; }

    public static String encode(ObjectMapper mapper, WidgetState state) {
        try {
            String json = mapper.writeValueAsString(state);
            if(json.getBytes(StandardCharsets.UTF_8).length>960 && state.description()!=null) {
                String description=state.description();
                for(int limit=Math.min(180,description.codePointCount(0,description.length()))-16;limit>=16;limit-=16) {
                    json=mapper.writeValueAsString(state.description(clip(description,limit)));
                    if(json.getBytes(StandardCharsets.UTF_8).length<=960) return json;
                }
                json=mapper.writeValueAsString(state.description(null));
            }
            // В answerWidgetQuery к состоянию добавляется обёртка результата.
            if (json.getBytes(StandardCharsets.UTF_8).length > 960) throw new IllegalArgumentException("WIDGET_STATE_TOO_LARGE");
            return json;
        } catch (JsonProcessingException e) { throw new IllegalArgumentException("INVALID_WIDGET_STATE", e); }
    }
    public static WidgetState withDescription(WidgetState state,String title,String description) {
        return withDescription(DESCRIPTION_MAPPER,state,title,description);
    }
    public static WidgetState withDescription(ObjectMapper mapper,WidgetState state,String title,String description) {
        if(description==null || description.isBlank() || normalized(description).equals(normalized(title))) return state.description(null);
        for(int limit=180;limit>=16;limit-=16) {
            var candidate=state.description(clip(description.strip(),limit));
            try {
                // Резервируем место под ключ и будущую ревизию, даже до сохранения контекста.
                if(mapper.writeValueAsBytes(candidate.identity(new java.util.UUID(0,0),999999999)).length<=960) return candidate;
            } catch(JsonProcessingException invalid) { throw new IllegalArgumentException("INVALID_WIDGET_STATE",invalid); }
        }
        return state.description(null);
    }
    private static String normalized(String value) {
        return value==null?"":value.strip().replaceAll("(?U)\\s+"," ").replaceAll("[.!?…]+$", "").strip().toLowerCase(Locale.ROOT);
    }
    public static String caption(WidgetState state) {
        StringBuilder text = new StringBuilder();
        if (state.heading()!=null) text.append("✓ ").append(state.heading()).append("\n");
        if (state.total()!=null) text.append(state.kind().equals("checkins")?"Дело ":"Действие ").append(state.page()+1).append(" из ").append(state.total())
                 .append(state.selected()==null?"":" · выбрано "+state.selected()).append("\n");
        if(!state.kind().equals("return") && !(state.kind().equals("checkins")&&state.terminal()))
            text.append(state.kind().equals("proposal")?"Предложение: ":"Задача: ");
        text.append(state.title());
        if(state.description()!=null && !state.description().isBlank()) text.append("\nОписание: ").append(state.description());
        for (var row : state.rows()) text.append('\n').append(row.get(0)).append(": ").append(row.get(1));
        if (state.warning()!=null) text.append("\n").append(state.warning());
        if (state.status() != null) text.append("\n\n").append(state.status());
        return clip(text.toString(), 950);
    }
    public static String time(OffsetDateTime value, ZoneId zone, Clock clock) {
        if (value == null) return "Без срока";
        var at = value.atZoneSameInstant(zone);
        var today = clock.instant().atZone(zone).toLocalDate();
        String date = at.toLocalDate().equals(today) ? "Сегодня" : at.toLocalDate().equals(today.plusDays(1)) ? "Завтра"
                : at.format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru")));
        return date + ", " + at.format(DateTimeFormatter.ofPattern("HH:mm"));
    }
    public static String times(java.util.List<OffsetDateTime> values, ZoneId zone, Clock clock) {
        var days = new java.util.LinkedHashMap<java.time.LocalDate,java.util.List<String>>();
        values.stream().map(OffsetDateTime::toInstant).distinct().sorted().forEach(instant -> {
            var local=instant.atZone(zone);
            days.computeIfAbsent(local.toLocalDate(),ignored->new java.util.ArrayList<>())
                    .add(local.format(DateTimeFormatter.ofPattern("HH:mm")));
        });
        return days.entrySet().stream().map(day -> {
            String date=time(day.getKey().atStartOfDay(zone).toOffsetDateTime(),zone,clock).split(", ")[0];
            return date+", "+String.join(", ",day.getValue());
        }).collect(java.util.stream.Collectors.joining(" · "));
    }
    public static String clip(String value, int limit) {
        if (value == null) return "";
        if (value.codePointCount(0, value.length()) <= limit) return value;
        return value.substring(0, value.offsetByCodePoints(0, limit - 1)) + "…";
    }
    private static String load() {
        try (var input = WidgetTemplate.class.getResourceAsStream("/telegram/munin-widget.html")) {
            if (input == null) throw new IllegalStateException("Missing widget template");
            byte[] html = input.readAllBytes();
            var deflater = new Deflater(9);
            try {
                deflater.setInput(html); deflater.finish();
                byte[] compressed = new byte[4097];
                int size = deflater.deflate(compressed);
                if (html.length > 32768 || !deflater.finished() || size > 4096)
                    throw new IllegalStateException("Widget template exceeds Telegram limits");
            } finally { deflater.end(); }
            return new String(html, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Cannot read widget template", e); }
    }
}
