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
@Import({PlanningComparisonLiveTest.LiveConfiguration.class,MultipleReminderIntegrationTest.TimeConfig.class})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MultipleReminderLiveTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p){p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
    @Autowired ExperimentalAgentLoop planner;@Autowired TaskService tasks;@Autowired UserService users;@Autowired ObjectMapper json;
    @Autowired PlanningComparisonLiveTest.LiveGateway gateway;
    @Test void preservesOneTaskAndBothReminderTimesWithEmptyAndCrowdedContexts() throws Exception {
        assumeTrue("true".equals(System.getenv("PLANNING_LIVE_APPROVED")));
        assumeTrue(System.getenv("GROQ_API_KEY")!=null && !System.getenv("GROQ_API_KEY").isBlank());
        UUID empty=users.findOrCreateByTelegram(854001,"empty_reminders","Тест",null).id();
        UUID crowded=users.findOrCreateByTelegram(854002,"crowded_reminders","Тест",null).id();
        for(int i=0;i<200;i++) tasks.createQuick(crowded,new CreateTaskRequest("Разобрать запись "+i,null,TaskPriority.MEDIUM,null,null,null,List.of(),null,TaskSource.MANUAL));
        var cases=List.of(MultipleReminderIntegrationTest.SOURCE,
                "В 23:00 нужно помыть кухню. Напомни через десять минут и дополнительно за десять минут до срока",
                "Создай задачу помыть кухню сегодня в 23:00. Напомни за 10 минут до срока и ещё напомни через 10 минут");
        var rows=new ArrayList<Map<String,Object>>();
        var expected=Set.of(MultipleReminderIntegrationTest.NOW.plusMinutes(10).toInstant(),MultipleReminderIntegrationTest.NOW.withHour(22).withMinute(50).withSecond(0).toInstant());
        for(UUID user:List.of(empty,crowded)) for(String source:cases) {
            var out=planner.run(PlanningMode.BATCH_PLAN,user,source,ZoneId.of("Europe/Moscow"),AssistantEntryPoint.CHAT);
            boolean correct=out.clarification()==null && out.rejections().isEmpty() && out.actions().size()==1 && out.actions().getFirst().type()==AssistantActionType.CREATE;
            Set<Instant> actual=new HashSet<>();
            if(correct) {
                var action=out.actions().getFirst();
                if(action.payload().get("reminder_ats") instanceof List<?> values) for(var value:values) actual.add(OffsetDateTime.parse(value.toString()).toInstant());
                var deadline=action.payload().get("deadline");
                correct=actual.equals(expected) && deadline!=null && OffsetDateTime.parse(deadline.toString()).toInstant().equals(MultipleReminderIntegrationTest.NOW.withHour(23).withMinute(0).withSecond(0).toInstant())
                        && action.payload().get("title").toString().toLowerCase(Locale.ROOT).contains("кухн");
            }
            var row=new LinkedHashMap<String,Object>();row.put("contextSize",user.equals(empty)?0:200);row.put("source",source);row.put("correct",correct);
            row.put("actions",out.actions());row.put("clarification",out.clarification());row.put("rejections",out.rejections());row.put("passes",out.passes());row.put("inputTokens",out.inputTokens());row.put("outputTokens",out.outputTokens());rows.add(row);
        }
        Path report=Path.of("build/reports/multiple-reminders-live.json");Files.createDirectories(report.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),Map.of("measurements",rows,"calls",gateway.attempts.get(),"errors",gateway.errors));
        System.out.println("Multiple reminders: "+rows.stream().filter(row->Boolean.TRUE.equals(row.get("correct"))).count()+"/"+rows.size()+", API calls: "+gateway.attempts.get());
        assertThat(rows).allMatch(row->Boolean.TRUE.equals(row.get("correct")));
        assertThat(gateway.errors).isEmpty();
    }
}
