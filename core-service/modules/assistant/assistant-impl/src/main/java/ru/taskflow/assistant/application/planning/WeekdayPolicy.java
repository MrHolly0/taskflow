package ru.taskflow.assistant.application.planning;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.regex.Pattern;

final class WeekdayPolicy {
    private static final Pattern WEEK=Pattern.compile("(?U)\\b(?:следующ|будущ)\\p{L}*\\s+недел\\p{L}*\\b");
    private static final Pattern DAY=Pattern.compile("(?U)\\b(?:в|во|к)\\s+(понедельник(?:у)?|вторник(?:у)?|сред[уае]|четверг(?:у)?|пятниц[уае]|суббот[уае]|воскресень[ею])\\b");
    private static final Pattern OTHER=Pattern.compile("(?U)\\b(?:сегодня|завтр[ао]|послезавтра|вчера|позавчера|через|эт\\p{L}*\\s+недел\\p{L}*|прошл\\p{L}*\\s+недел\\p{L}*|понедель\\p{L}*|вторник\\p{L}*|сред[ауеы]|четверг\\p{L}*|пятниц\\p{L}*|суббот\\p{L}*|воскресен\\p{L}*|январ\\p{L}*|феврал\\p{L}*|март\\p{L}*|апрел\\p{L}*|мая|июн\\p{L}*|июл\\p{L}*|август\\p{L}*|сентябр\\p{L}*|октябр\\p{L}*|ноябр\\p{L}*|декабр\\p{L}*|числа|число)\\b|\\d[./-]\\d");
    private static final Pattern NEGATION=Pattern.compile("(?U)\\bне\\s+(?:(?:в|во|к|на)\\s+)?(?:следующ|будущ|понедель|вторник|сред|четверг|пятниц|суббот|воскресен)");
    private WeekdayPolicy() {}

    static Optional<LocalDate> nextWeek(String source,LocalDate today) {
        if(NEGATION.matcher(source).find()) return Optional.empty();
        var week=WEEK.matcher(source);var day=DAY.matcher(source);
        if(!week.find()||!day.find()) return Optional.empty();
        String name=day.group(1);
        String rest=week.replaceAll(" ");rest=DAY.matcher(rest).replaceAll(" ");
        if(week.reset().results().count()!=1||day.reset().results().count()!=1||OTHER.matcher(rest).find()) return Optional.empty();
        DayOfWeek weekday=name.startsWith("понедель")?DayOfWeek.MONDAY:name.startsWith("вторник")?DayOfWeek.TUESDAY:
                name.startsWith("сред")?DayOfWeek.WEDNESDAY:name.startsWith("четверг")?DayOfWeek.THURSDAY:
                name.startsWith("пятниц")?DayOfWeek.FRIDAY:name.startsWith("суббот")?DayOfWeek.SATURDAY:DayOfWeek.SUNDAY;
        return Optional.of(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusDays(weekday.getValue()-1));
    }
}
