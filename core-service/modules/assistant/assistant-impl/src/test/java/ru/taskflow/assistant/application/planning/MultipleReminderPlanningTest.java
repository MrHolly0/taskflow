package ru.taskflow.assistant.application.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.application.TaskContextWindow;
import ru.taskflow.task.api.TaskService;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MultipleReminderPlanningTest {
    final ObjectMapper json=new ObjectMapper();
    final OffsetDateTime now=OffsetDateTime.parse("2030-11-11T21:11:31+03:00");
    final String source="Напомни через 10 минут, что мне в 23:00 надо помыть кухню. Дополнительно напомни за 10 минут до 23:00";
    PlanningSession session() {
        return new PlanningSession(mock(TaskService.class),UUID.randomUUID(),json,new TaskContextWindow("",Map.of(),Map.of()),
                ZoneId.of("Europe/Moscow"),source,false,true,now);
    }
    @Test void oneCreateKeepsBothIndependentReminderTimes() throws Exception {
        var result=session().expand(json.readTree("""
                {"steps":[{"action":{"type":"create","title":"Помыть кухню","deadline":"2030-11-11T23:00:00+03:00",
                "reminder_after_minutes":10,"reminder_before_deadline_minutes":10}}]}
                """));
        assertThat(result.path("actions").size()).isEqualTo(1);
        var action=result.path("actions").get(0);
        assertThat(action.path("type").asText()).isEqualTo("create");
        assertThat(action.path("reminder_ats").get(0).asText()).isEqualTo("2030-11-11T21:21:31+03:00");
        assertThat(action.path("reminder_ats").get(1).asText()).isEqualTo("2030-11-11T22:50+03:00");
        assertThat(action.has("task_ref")).isFalse();
    }
    @Test void absoluteReminderAndDeadlineOffsetCanCoexistInOneCreation() throws Exception {
        var result=session().expand(json.readTree("""
                {"steps":[{"action":{"type":"create","title":"Помыть кухню","deadline":"2030-11-11T23:00:00+03:00",
                "reminder_at":"2030-11-11T21:21:31+03:00","reminder_before_deadline_minutes":10}}]}
                """));
        assertThat(result.path("actions").get(0).path("reminder_ats").size()).isEqualTo(2);
    }
    @Test void missingAdditionalReminderIsRejectedInsteadOfSilentlyDropped() throws Exception {
        var plan=json.readTree("""
                {"steps":[{"action":{"type":"create","title":"Помыть кухню","deadline":"2030-11-11T23:00:00+03:00","reminder_after_minutes":10}}]}
                """);
        assertThatThrownBy(()->session().expand(plan)).isInstanceOf(PlanningException.class);
    }
    @Test void equalInstantsWithDifferentOffsetsDoNotCreateDuplicates() throws Exception {
        var result=session().expand(json.readTree("""
                {"steps":[{"action":{"type":"create","title":"Помыть кухню","reminder_ats":["2030-11-11T22:50:00+03:00","2030-11-11T19:50:00Z"]}}]}
                """));
        assertThat(result.path("actions").get(0).path("reminder_at").asText()).isEqualTo("2030-11-11T22:50+03:00");
        assertThat(result.path("actions").get(0).has("reminder_ats")).isFalse();
    }
}
