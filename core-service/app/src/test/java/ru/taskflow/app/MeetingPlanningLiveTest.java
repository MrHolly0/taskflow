package ru.taskflow.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.taskflow.assistant.api.*;
import ru.taskflow.assistant.application.planning.*;
import ru.taskflow.task.api.*;
import ru.taskflow.task.api.dto.*;
import ru.taskflow.user.api.UserService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("live")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"app.telegram.bot-token=","app.notification.engagement-enabled=false"})
@Import({PlanningComparisonLiveTest.LiveConfiguration.class,MeetingPlanningIntegrationTest.TimeConfig.class})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MeetingPlanningLiveTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p){p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
    @Autowired ExperimentalAgentLoop planner;@Autowired TaskService tasks;@Autowired UserService users;@Autowired ObjectMapper json;
    @Autowired PlanningComparisonLiveTest.LiveGateway gateway;
    record Scenario(String id,String text,String deadline,String reminder,String street,String house,String room) {}
    @Test void preservesMeetingDatesAndLocationsWithEmptyAndCrowdedContexts() throws Exception {
        assumeTrue("true".equals(System.getenv("PLANNING_LIVE_APPROVED")));
        assumeTrue(System.getenv("GROQ_API_KEY")!=null&&!System.getenv("GROQ_API_KEY").isBlank());
        UUID empty=users.findOrCreateByTelegram(854011,"empty_meetings","Тест",null).id();
        UUID crowded=users.findOrCreateByTelegram(854012,"crowded_meetings","Тест",null).id();
        for(int i=0;i<200;i++) tasks.createQuick(crowded,new CreateTaskRequest("Разобрать запись "+i,null,TaskPriority.MEDIUM,null,null,null,List.of(),null,TaskSource.MANUAL));
        var cases=List.of(
            new Scenario("tuesday","На следующей неделе во вторник мне надо быть на встрече с куратором, напомни мне за день до этого. Встреча в 14:00 на Музейной 12 в аудитории 208","2026-11-17T14:00:00+03:00","2026-11-16T14:00:00+03:00","музейн","12","208"),
            new Scenario("wednesday","В среду на будущей неделе встреча с руководителем в 18:30 на Садовой 24, кабинет 305. Напомни за два часа до встречи","2026-11-18T18:30:00+03:00","2026-11-18T16:30:00+03:00","садов","24","305"),
            new Scenario("monday","На следующей неделе в понедельник в 08:00 консультация в аудитории 412. Напомни за день до начала","2026-11-16T08:00:00+03:00","2026-11-15T08:00:00+03:00",null,null,"412"),
            new Scenario("no-location","На следующей неделе в пятницу встреча в 16:00. Напомни за час до встречи","2026-11-20T16:00:00+03:00","2026-11-20T15:00:00+03:00",null,null,null));
        var rows=new ArrayList<Map<String,Object>>();
        for(int repeat=1;repeat<=2;repeat++) for(UUID user:List.of(empty,crowded)) for(var scenario:cases) {
            var out=planner.run(PlanningMode.BATCH_PLAN,user,scenario.text(),ZoneId.of("Europe/Moscow"),AssistantEntryPoint.CHAT);
            boolean correct=out.clarification()==null&&out.rejections().isEmpty()&&out.actions().size()==1&&out.actions().getFirst().type()==AssistantActionType.CREATE;
            if(correct) {
                var payload=out.actions().getFirst().payload();String description=Objects.toString(payload.get("description"),"").toLowerCase(Locale.ROOT);
                correct=sameTime(payload.get("deadline"),scenario.deadline())&&sameTime(payload.get("reminder_at"),scenario.reminder());
                if(scenario.room()!=null) correct&=description.contains(scenario.room());
                if(scenario.street()!=null) correct&=description.contains(scenario.street())&&description.contains(scenario.house());
                if(scenario.room()==null) correct&=description.isBlank();
            }
            var row=new LinkedHashMap<String,Object>();row.put("scenario",scenario.id());row.put("repeat",repeat);row.put("contextSize",user.equals(empty)?0:200);
            row.put("correct",correct);row.put("actions",out.actions());row.put("clarification",out.clarification());row.put("rejections",out.rejections());
            row.put("passes",out.passes());row.put("inputTokens",out.inputTokens());row.put("outputTokens",out.outputTokens());rows.add(row);
            Thread.sleep(1200);
        }
        Path report=Path.of("build/reports/meeting-planning-live.json");Files.createDirectories(report.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),Map.of("measurements",rows,"calls",gateway.attempts.get(),"errors",gateway.errors,"traces",gateway.responses));
        System.out.println("Meetings: "+rows.stream().filter(row->Boolean.TRUE.equals(row.get("correct"))).count()+"/"+rows.size()+", API calls: "+gateway.attempts.get());
        assertThat(rows).allMatch(row->Boolean.TRUE.equals(row.get("correct")));assertThat(gateway.errors).isEmpty();
    }
    private boolean sameTime(Object value,String expected) {
        try {return value!=null&&OffsetDateTime.parse(value.toString()).toInstant().equals(OffsetDateTime.parse(expected).toInstant());}
        catch(java.time.format.DateTimeParseException e){return false;}
    }
}
