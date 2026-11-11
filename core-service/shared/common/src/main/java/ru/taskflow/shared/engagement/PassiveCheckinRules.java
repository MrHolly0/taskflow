package ru.taskflow.shared.engagement;

public final class PassiveCheckinRules {
    private PassiveCheckinRules() {}
    public static String allowedSql(String task) {
        if(!task.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid task alias");
        return "(NOT EXISTS(SELECT 1 FROM reminders promised WHERE promised.task_id="+task+".id"
            +" AND promised.purpose='OUTCOME_CHECK' AND promised.deadline_snapshot="+task+".deadline"
            +" AND promised.status IN ('PENDING','DISMISSED') AND ("+task+".status='TODO' OR "+task+".started_at IS NULL OR promised.fire_at>="+task+".started_at))"
            +" AND NOT EXISTS(SELECT 1 FROM engagement_prompts notice WHERE notice.task_id="+task+".id"
            +" AND notice.kind='DEADLINE_CHECKIN' AND notice.deadline_snapshot="+task+".deadline"
            +" AND notice.cancelled_at IS NULL AND notice.sent_at IS NOT NULL AND ("+task+".status='TODO' OR "+task+".started_at IS NULL OR "+task+".started_at<=notice.sent_at))"
            +" AND NOT EXISTS(SELECT 1 FROM scheduled_notifications delivered WHERE delivered.task_id="+task+".id AND delivered.user_id="+task+".user_id"
            +" AND delivered.payload_type='TASK_REMINDER' AND delivered.sent AND delivered.fire_at="+task+".deadline"
            +" AND delivered.sent_at IS NOT NULL AND ("+task+".status='TODO' OR "+task+".started_at IS NULL OR "+task+".started_at<=delivered.sent_at)))";
    }
}
