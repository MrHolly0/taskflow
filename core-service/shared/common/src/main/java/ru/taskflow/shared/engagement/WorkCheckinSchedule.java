package ru.taskflow.shared.engagement;

public final class WorkCheckinSchedule {
    private WorkCheckinSchedule() {}
    public static String dueSql(String alias) {
        if (!alias.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid table alias");
        return "GREATEST("+alias+".started_at + INTERVAL '15 minutes', LEAST("+alias+".started_at + "
                +"(CASE WHEN "+alias+".estimate_minutes > 0 THEN LEAST("+alias+".estimate_minutes,480)+15 ELSE 60 END) * INTERVAL '1 minute', "
                +"COALESCE("+alias+".deadline + INTERVAL '30 minutes', 'infinity'::timestamptz)))";
    }
}
