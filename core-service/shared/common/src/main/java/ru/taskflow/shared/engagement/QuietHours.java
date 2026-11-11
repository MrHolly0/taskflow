package ru.taskflow.shared.engagement;

import java.time.*;

public final class QuietHours {
    private QuietHours() {}
    public static OffsetDateTime deferUntil(Instant now,ZoneId zone,boolean enabled,String start,String end) {
        if(!enabled) return null;
        var from=LocalTime.parse(start);var to=LocalTime.parse(end);
        if(from.equals(to)) return null;
        var local=now.atZone(zone);var time=local.toLocalTime();
        boolean overnight=from.isAfter(to);
        boolean quiet=overnight?(!time.isBefore(from)||time.isBefore(to)):(!time.isBefore(from)&&time.isBefore(to));
        if(!quiet) return null;
        var date=overnight&&!time.isBefore(from)?local.toLocalDate().plusDays(1):local.toLocalDate();
        var endLocal=date.atTime(to);var rules=zone.getRules();
        var boundary=rules.getValidOffsets(endLocal).isEmpty()?rules.getTransition(endLocal).getDateTimeAfter().atZone(zone):endLocal.atZone(zone);
        if(!boundary.toInstant().isAfter(now)) boundary=boundary.withLaterOffsetAtOverlap();
        return boundary.toOffsetDateTime();
    }
    public static String allowedSql() {
        return "(NOT s.quiet_hours_enabled OR CASE WHEN s.quiet_hours_start>s.quiet_hours_end "
                +"THEN local.local_time>=s.quiet_hours_end::time AND local.local_time<s.quiet_hours_start::time "
                +"ELSE NOT(local.local_time>=s.quiet_hours_start::time AND local.local_time<s.quiet_hours_end::time) END)";
    }
}
