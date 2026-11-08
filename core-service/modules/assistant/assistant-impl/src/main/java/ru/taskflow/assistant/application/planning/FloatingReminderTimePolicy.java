package ru.taskflow.assistant.application.planning;

import java.time.LocalTime;
import java.util.Set;
import java.util.regex.Pattern;

final class FloatingReminderTimePolicy {
    private static final Pattern DATE = Pattern.compile("(?U)\\b(?:сегодн|завтр|вчера|через|назад|позавчера|срок|дедлайн|начал|понедель|вторник|сред|четверг|пятниц|суббот|воскресен|январ|феврал|март|апрел|ма[йяе]|июн|июл|август|сентябр|октябр|ноябр|декабр|числ|недел|месяц|следующ|грядущ|перв|втор|трет|треть|четв[её]рт|пят|шест|седьм|восьм|девят|десят|одиннадцат|двенадцат|тринадцат|четырнадцат|пятнадцат|шестнадцат|семнадцат|восемнадцат|девятнадцат|двадцат|тридцат)\\p{L}*\\b|\\b(?:за|до)\\b");
    private static final Pattern TIME = Pattern.compile("(?U)\\b(?:в\\s+|на\\s+(?=\\d{1,2}:))([01]?[0-9]|2[0-3])(?::([0-5][0-9]))?\\s*(утра|дня|вечера|ночи)?\\b");
    private static final Pattern NUMBER = Pattern.compile("\\p{N}");
    private FloatingReminderTimePolicy() {}

    static LocalTime from(String text) {
        if (DATE.matcher(text).find()) return null;
        var match = TIME.matcher(text);
        if (!match.find()) return null;
        int hour = Integer.parseInt(match.group(1)), minute = match.group(2) == null ? 0 : Integer.parseInt(match.group(2));
        String part = match.group(3);
        String rest = text.substring(0,match.start()) + " " + text.substring(match.end());
        if (match.find() || NUMBER.matcher(rest).find()) return null;
        if (part != null && (hour == 0 || hour > 12 || hour == 12 && Set.of("утра","вечера").contains(part))) return null;
        if (Set.of("дня","вечера").contains(part == null ? "" : part) && hour < 12) hour += 12;
        if ("ночи".equals(part) && hour == 12) hour = 0;
        return LocalTime.of(hour,minute);
    }
}
