package ru.taskflow.shared.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class WidgetKeyboard {
    private WidgetKeyboard() {}
    public static Map<String,Object> of(WidgetContext context, String appUrl) {
        var rows = new ArrayList<List<Map<String,String>>>();
        var state = context.state();
        String key = state.key() + ":";
        if (context.expanded() && !state.terminal()) {
            switch (state.kind()) {
                case "proposal" -> {
                    if (state.total() != null) {
                        var navigation = new ArrayList<Map<String,String>>();
                        if (state.page() > 0) navigation.add(button("←",key+"pg"+(state.page()-1)));
                        navigation.add(button(Boolean.TRUE.equals(state.accepted())?"Убрать":"Выбрать",key+(Boolean.TRUE.equals(state.accepted())?"sel0":"sel1")));
                        if (state.page()+1<state.total()) navigation.add(button("→",key+"pg"+(state.page()+1)));
                        rows.add(navigation);
                    }
                    if (state.selected() == null || state.selected() > 0) rows.add(List.of(button("Применить выбранное",key+"a"),button("Отклонить",key+"r")));
                    else rows.add(List.of(button("Отклонить",key+"r")));
                }
                case "reminder", "checkin" -> {
                    rows.add(List.of(button("Готово",key+"done"),button("Ещё в работе",key+"working")));
                    rows.add(List.of(button("Напомнить через час",key+"later")));
                }
                case "return" -> rows.add(List.of(button("Не напоминать об этом",key+"mute")));
                default -> { }
            }
        } else if (!state.terminal()) rows.add(List.of(button("Подробнее",key+"details")));
        if (appUrl != null && !appUrl.isBlank()) rows.add(List.of(Map.of("text",context.taskId()==null?"Открыть Мунин":"Открыть задачу",
                "url",appUrl.replaceAll("/+$", "")+(context.taskId()==null?"":"/?task="+context.taskId()))));
        return Map.of("inline_keyboard",rows);
    }
    private static Map<String,String> button(String text,String data) { return Map.of("text",text,"callback_data",data); }
}
