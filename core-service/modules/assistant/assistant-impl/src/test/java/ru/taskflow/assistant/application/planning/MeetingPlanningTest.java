package ru.taskflow.assistant.application.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.application.TaskContextWindow;
import ru.taskflow.task.api.TaskService;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MeetingPlanningTest {
    final ObjectMapper json=new ObjectMapper();
    final String source="На следующей неделе во вторник мне надо быть на встрече с куратором, напомни мне за день до этого. Встреча в 14:00 на музейной 12 в аудитории 208";
    PlanningSession session(String source,OffsetDateTime now,ZoneId zone) {
        return new PlanningSession(mock(TaskService.class),UUID.randomUUID(),json,new TaskContextWindow("",Map.of(),Map.of()),zone,source,false,true,now);
    }
    @Test void correctsTheWeekdayBeforeCalculatingTheReminderAndKeepsMeetingDetails() throws Exception {
        var s=session(source,OffsetDateTime.parse("2026-11-12T14:37:00+03:00"),ZoneId.of("Europe/Moscow"));
        var result=s.expand(json.readTree("""
                {"steps":[{"action":{"type":"create","title":"Встреча с куратором",
                  "description":"Музейная, 12, аудитория 208","deadline":"2026-11-20T14:00:00+03:00",
                  "reminder_before_deadline_minutes":1440}}]}
                """));
        var action=result.path("actions").get(0);
        assertThat(OffsetDateTime.parse(action.path("deadline").asText())).isEqualTo(OffsetDateTime.parse("2026-11-17T14:00:00+03:00"));
        assertThat(OffsetDateTime.parse(action.path("reminder_at").asText())).isEqualTo(OffsetDateTime.parse("2026-11-16T14:00:00+03:00"));
        assertThat(action.path("description").asText()).contains("Музейная","12","208");
    }
    @Test void missingRoomDetailsTriggersRepairInsteadOfAutomaticCreation() throws Exception {
        var s=session(source,OffsetDateTime.parse("2026-11-12T14:37:00+03:00"),ZoneId.of("Europe/Moscow"));
        var plan=json.readTree("""
                {"steps":[{"action":{"type":"create","title":"Встреча с куратором",
                  "deadline":"2026-11-20T14:00:00+03:00","reminder_before_deadline_minutes":1440}}]}
                """);
        assertThatThrownBy(()->s.expand(plan)).isInstanceOf(PlanningException.class).hasMessage("INVALID_ACTION");
    }
    @Test void roomWithoutTheAddressIsNotACompleteDescription() throws Exception {
        var s=session(source,OffsetDateTime.parse("2026-11-12T14:37:00+03:00"),ZoneId.of("Europe/Moscow"));
        var plan=json.readTree("""
                {"steps":[{"action":{"type":"create","title":"Встреча с куратором","description":"Аудитория 208",
                  "deadline":"2026-11-20T14:00:00+03:00","reminder_before_deadline_minutes":1440}}]}
                """);
        assertThatThrownBy(()->s.expand(plan)).isInstanceOf(PlanningException.class).hasMessage("INVALID_ACTION");
    }
    @Test void meetingLocationInTheTitleDoesNotReplaceItsDescription() throws Exception {
        var s=session("Консультация на следующей неделе в понедельник в 08:00 в аудитории 412",OffsetDateTime.parse("2026-11-12T14:37:00+03:00"),ZoneId.of("Europe/Moscow"));
        var plan=json.readTree("""
                {"steps":[{"action":{"type":"create","title":"Консультация в аудитории 412","deadline":"2026-11-16T08:00:00+03:00"}}]}
                """);
        assertThatThrownBy(()->s.expand(plan)).isInstanceOf(PlanningException.class).hasMessage("INVALID_ACTION");
    }
    @Test void anAbsoluteReminderDoesNotLetAnIncorrectWeekdayBypassValidation() throws Exception {
        var s=session(source,OffsetDateTime.parse("2026-11-12T14:37:00+03:00"),ZoneId.of("Europe/Moscow"));
        var plan=json.readTree("""
                {"steps":[{"action":{"type":"create","title":"Встреча с куратором","description":"Музейная, 12, аудитория 208",
                  "deadline":"2026-11-20T14:00:00+03:00","reminder_at":"2026-11-19T14:00:00+03:00"}}]}
                """);
        assertThatThrownBy(()->s.expand(plan)).isInstanceOf(PlanningException.class).hasMessage("INVALID_ACTION");
    }
    @Test void anArrayOfRemindersDoesNotBypassTheCalendarConsistencyCheck() throws Exception {
        var s=session(source,OffsetDateTime.parse("2026-11-12T14:37:00+03:00"),ZoneId.of("Europe/Moscow"));
        var plan=json.readTree("""
                {"steps":[{"action":{"type":"create","title":"Встреча с куратором","description":"Музейная, 12, аудитория 208",
                  "deadline":"2026-11-20T14:00:00+03:00","reminder_ats":["2026-11-19T14:00:00+03:00"]}}]}
                """);
        assertThatThrownBy(()->s.expand(plan)).isInstanceOf(PlanningException.class).hasMessage("INVALID_ACTION");
    }
    @Test void preliminaryReminderKeepsItsOwnDayInsteadOfTakingTheMeetingsWeekday() throws Exception {
        var service=mock(TaskService.class);var now=OffsetDateTime.parse("2026-11-12T14:37:00+03:00");
        var task=new ru.taskflow.task.api.dto.TaskResponse(UUID.randomUUID(),"Встреча",null,
                ru.taskflow.task.api.TaskPriority.MEDIUM,ru.taskflow.task.api.TaskStatus.TODO,OffsetDateTime.parse("2026-11-17T14:00:00+03:00"),
                null,ru.taskflow.task.api.TaskSource.MANUAL,null,null,List.of(),now,now,null,List.of());
        when(service.findAssistantContext(any(),eq(12))).thenReturn(List.of(task));
        var s=new PlanningSession(service,UUID.randomUUID(),json,new TaskContextWindow("",Map.of(),Map.of()),ZoneId.of("Europe/Moscow"),
                "Напомни накануне встречи во вторник на следующей неделе в 14:00",false,true,now);
        s.preview();
        var result=s.expand(json.readTree("""
                {"steps":[{"select":{"ref":"T1"},"action":{"type":"remind","reminder_at":"2026-11-16T14:00:00+03:00"}}]}
                """));
        assertThat(OffsetDateTime.parse(result.path("actions").get(0).path("reminder_at").asText()))
                .isEqualTo(OffsetDateTime.parse("2026-11-16T14:00:00+03:00"));
    }
    @Test void usesTheUsersCalendarDayAcrossTheUtcDateBoundary() throws Exception {
        var s=session("на следующей неделе во вторник встреча в 14:00",OffsetDateTime.parse("2026-11-15T23:30:00Z"),ZoneId.of("Asia/Tokyo"));
        var result=s.expand(json.readTree("""
                {"steps":[{"action":{"type":"create","title":"Встреча","deadline":"2026-11-17T14:00:00+09:00"}}]}
                """));
        assertThat(OffsetDateTime.parse(result.path("actions").get(0).path("deadline").asText()))
                .isEqualTo(OffsetDateTime.parse("2026-11-24T14:00:00+09:00"));
    }
    @Test void doesNotInventRoomDetailsWhenNoneWereProvided() throws Exception {
        var s=session("на следующей неделе во вторник встреча в 14:00",OffsetDateTime.parse("2026-11-12T14:37:00+03:00"),ZoneId.of("Europe/Moscow"));
        var result=s.expand(json.readTree("""
                {"steps":[{"action":{"type":"create","title":"Встреча","deadline":"2026-11-20T14:00:00+03:00"}}]}
                """));
        assertThat(result.path("actions").get(0).has("description")).isFalse();
    }
}
