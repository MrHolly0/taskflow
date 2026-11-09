package ru.taskflow.assistant.application.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;

final class DeadlinePrecisionPolicy {
    private static final Pattern CLOCK = Pattern.compile("(?U)(?:\\b[0-2]?\\d:[0-5]\\d\\b|\\b(?:утро|утром|утра|утру|вечер|вечером|вечера|вечеру|дн[её]м|ночью|полудн\\p{L}*|полдень|полноч\\p{L}*)\\b|\\b(?:в|к|на)\\s+(?:[01]?\\d|2[0-3]|один|два|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать|тринадцать|четырнадцать|пятнадцать|шестнадцать|семнадцать|восемнадцать|девятнадцать|двадцать)\\b(?!\\s*[./-]\\s*\\d)(?!\\s*(?:числ\\p{L}*|январ\\p{L}*|феврал\\p{L}*|март\\p{L}*|апрел\\p{L}*|мая|июн\\p{L}*|июл\\p{L}*|август\\p{L}*|сентябр\\p{L}*|октябр\\p{L}*|ноябр\\p{L}*|декабр\\p{L}*))|\\b\\d+\\s*(?:ч|мин|сек)\\b|\\b(?:минут\\p{L}*|час(?:а|ов|у|ом|е|ы)?|полчаса|секунд(?:а|ы|у|е|ой|ами|ах|ам)?)\\b)");
    private static final Pattern SPOKEN_CLOCK = Pattern.compile("(?U)\\b(?:в|к)\\s+(?:[0-2]?\\d[.]\\d{2}\\b|(?:одному|двум|тр[её]м|четыр[её]м|пяти|шести|семи|восьми|девяти|десяти|одиннадцати|двенадцати|тринадцати|четырнадцати|пятнадцати|шестнадцати|семнадцати|восемнадцати|девятнадцати|двадцати)\\b)|\\b(?:половин\\p{L}*|четверт\\p{L}*|без пяти|без десяти)\\b");
    private DeadlinePrecisionPolicy() {}

    static void normalize(ArrayNode actions, String source, ZoneId zone) {
        if (CLOCK.matcher(source.toLowerCase(Locale.ROOT)).find() || SPOKEN_CLOCK.matcher(source.toLowerCase(Locale.ROOT)).find()) return;
        for (JsonNode action : actions) {
            if (!"create".equals(action.path("type").asText()) || !action.hasNonNull("deadline")) continue;
            try {
                var date = OffsetDateTime.parse(action.path("deadline").asText()).atZoneSameInstant(zone).toLocalDate();
                ((ObjectNode) action).put("deadline", date.atTime(23, 59).atZone(zone).toOffsetDateTime().toString());
                ((ObjectNode) action).put("deadline_time_inferred", true);
            } catch (java.time.format.DateTimeParseException ignored) {
                // Формат проверяется общим валидатором, ошибку не скрываем.
            }
        }
    }
}
