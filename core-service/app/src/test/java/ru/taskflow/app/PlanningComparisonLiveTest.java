package ru.taskflow.app;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.taskflow.assistant.api.*;
import ru.taskflow.assistant.application.*;
import ru.taskflow.assistant.application.planning.*;
import ru.taskflow.nlp.api.*;
import ru.taskflow.task.api.*;
import ru.taskflow.task.api.dto.*;
import ru.taskflow.user.api.UserService;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Tag("live")
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"app.telegram.bot-token=", "app.public-base-url="})
@Import(PlanningComparisonLiveTest.LiveConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PlanningComparisonLiveTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired AgentLoop direct;
    @Autowired ExperimentalAgentLoop experimental;
    @Autowired TaskService tasks;
    @Autowired UserService users;
    @Autowired ObjectMapper json;
    @Autowired LiveGateway gateway;
    @MockBean AssistantRateLimiter limiter;
    record Scenario(String id,String request,AssistantActionType type,Set<UUID> targets,int count,String dateField,Instant date,boolean clarify,boolean decline) {}
    record Measurement(String scenario,PlanningMode mode,boolean correct,int actions,int passes,int inputTokens,int outputTokens,long elapsedMs,List<String> rejections,String error) {}

    @Test void compareSyntheticRequestsWithoutApplyingChanges() throws Exception {
        assumeTrue("true".equals(System.getenv("PLANNING_LIVE_APPROVED")),"Explicit opt-in required");
        assumeTrue(System.getenv("GROQ_API_KEY")!=null&&!System.getenv("GROQ_API_KEY").isBlank(),"GROQ_API_KEY required");
        when(limiter.allow(any())).thenReturn(true);
        long telegram=ThreadLocalRandom.current().nextLong(1000000000L,9000000000L);
        UUID user=users.findOrCreateByTelegram(telegram,"comparison_"+telegram,"Test","User").id();
        var document=task(user,"Отправить документы в деканат","Личное",null);
        var report=task(user,"Подготовить отчёт для кафедры","Учёба",OffsetDateTime.now().minusDays(1));
        var review=task(user,"Проверить расчёты диплома","Учёба",OffsetDateTime.now().minusDays(1));
        task(user,"Подготовить отчёт по практике","Работа",null);
        for(int i=0;i<85;i++)task(user,"Архивный пункт "+i,"Работа",OffsetDateTime.now().minusDays(20));
        ZoneId zone=ZoneId.of("Europe/Moscow");
        var tomorrow=LocalDate.now(zone).plusDays(1).atTime(18,0).atZone(zone).toInstant();
        List<Scenario> scenarios=List.of(
            new Scenario("create","Добавь задачу: купить блокнот завтра в 18:00",AssistantActionType.CREATE,Set.of(),1,"deadline",tomorrow,false,false),
            new Scenario("hidden-complete","Я отправил документы в деканат, отметь задачу выполненной",AssistantActionType.COMPLETE,Set.of(document.id()),1,null,null,false,false),
            new Scenario("group-overdue","Перенеси сроки всех просроченных задач из группы Учёба на 15 ноября 2030 года, 18:00",AssistantActionType.RESCHEDULE,Set.of(report.id(),review.id()),2,"new_deadline",OffsetDateTime.parse("2030-11-15T18:00:00+03:00").toInstant(),false,false),
            new Scenario("ambiguous","Перенеси отчёт на завтра",null,Set.of(),0,null,null,true,false),
            new Scenario("reminder","Напомни о задаче отправить документы в деканат 15 ноября 2030 года в 12:00",AssistantActionType.REMIND,Set.of(document.id()),1,"reminder_at",OffsetDateTime.parse("2030-11-15T12:00:00+03:00").toInstant(),false,false),
            new Scenario("chitchat","Спасибо!",null,Set.of(),0,null,null,false,true)
        );
        List<Measurement> measurements=new ArrayList<>();
        boolean followup = "true".equals(System.getenv("PLANNING_LIVE_FOLLOWUP"));
        if (followup) scenarios = scenarios.stream().filter(s -> Set.of("hidden-complete", "group-overdue", "reminder").contains(s.id())).toList();
        List<PlanningMode> modes = followup ? List.of(PlanningMode.BATCH_PLAN, PlanningMode.CODE_MODE) : List.of(PlanningMode.values());
        for(Scenario scenario:scenarios)for(PlanningMode mode:modes) {
            long start=System.nanoTime();
            try {
                AgentOutcome out=mode==PlanningMode.DIRECT?direct.run(user,scenario.request(),zone,AssistantEntryPoint.CHAT)
                    :experimental.run(mode,user,scenario.request(),zone,AssistantEntryPoint.CHAT);
                Set<UUID> targets=new HashSet<>();out.actions().forEach(a->{if(a.targetTaskId()!=null)targets.add(a.targetTaskId());});
                boolean correct=scenario.clarify()?out.ambiguous()||out.clarification()!=null&&out.rejections().stream().allMatch("AMBIGUOUS"::equals)
                    :scenario.decline()?out.declineReason()==DeclineReason.CHITCHAT
                    :out.actions().size()==scenario.count()&&targets.equals(scenario.targets())&&out.rejections().isEmpty()
                        &&out.actions().stream().allMatch(a->a.type()==scenario.type()&&dateMatches(a.payload(),scenario));
                if(scenario.type()==AssistantActionType.CREATE)correct&=out.actions().stream().allMatch(a->String.valueOf(a.payload().get("title")).toLowerCase(Locale.ROOT).contains("блокнот"));
                measurements.add(new Measurement(scenario.id(),mode,correct,out.actions().size(),out.passes(),out.inputTokens(),out.outputTokens(),
                    (System.nanoTime()-start)/1000000,out.rejections(),out.llmFailed()||out.rejections().contains("MODEL_UNAVAILABLE")?"MODEL_UNAVAILABLE":null));
            }catch(Exception e){measurements.add(new Measurement(scenario.id(),mode,false,0,0,0,0,(System.nanoTime()-start)/1000000,List.of(),e.getClass().getSimpleName()));}
            Thread.sleep(500);
        }
        Path path=Path.of(followup ? "build/reports/planning-followup.json" : "build/reports/planning-comparison.json");Files.createDirectories(path.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of("model",gateway.model,"httpAttempts",gateway.attempts.get(),"apiErrors",gateway.errors,"measurements",measurements,"responses",gateway.responses));
        for(PlanningMode mode:PlanningMode.values()) {
            var rows=measurements.stream().filter(r->r.mode()==mode).toList();
            System.out.printf(Locale.ROOT,"%s correct=%d/%d input=%d output=%d avgMs=%.0f%n",mode,rows.stream().filter(Measurement::correct).count(),rows.size(),
                rows.stream().mapToInt(Measurement::inputTokens).sum(),rows.stream().mapToInt(Measurement::outputTokens).sum(),rows.stream().mapToLong(Measurement::elapsedMs).average().orElse(0));
        }
        assertThat(tasks.findById(user,document.id()).status()).isEqualTo(TaskStatus.TODO);
        assertThat(tasks.findById(user,report.id()).deadline()).isBefore(OffsetDateTime.now());
        assertThat(measurements).anyMatch(m->m.inputTokens()>0);
    }
    boolean dateMatches(Map<String,Object> payload,Scenario scenario) {
        if(scenario.dateField()==null)return true;
        try{return OffsetDateTime.parse(String.valueOf(payload.get(scenario.dateField()))).toInstant().equals(scenario.date());}
        catch(Exception e){return false;}
    }
    TaskResponse task(UUID user,String title,String group,OffsetDateTime deadline) {
        return tasks.create(user,new CreateTaskRequest(title,null,TaskPriority.MEDIUM,deadline,null,group,List.of(),15,TaskSource.MANUAL,null));
    }
    @TestConfiguration static class LiveConfiguration {
        @Bean @Primary LiveGateway comparisonGateway(ObjectMapper mapper){return new LiveGateway(mapper);}
    }
    static class LiveGateway implements NlpGatewayService {
        final AtomicInteger attempts=new AtomicInteger();
        final List<String> errors=new ArrayList<>();
        final List<JsonNode> responses=new ArrayList<>();
        final int callLimit = Math.min(25, Integer.parseInt(System.getenv().getOrDefault("PLANNING_LIVE_CALL_LIMIT", "25")));
        final String model=System.getenv().getOrDefault("PLANNING_BENCH_MODEL","openai/gpt-oss-120b");
        final RestClient client;
        final ObjectMapper mapper;
        LiveGateway(ObjectMapper mapper){
            this.mapper=mapper;var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(5000);factory.setReadTimeout(45000);
            client=RestClient.builder().requestFactory(factory).baseUrl("https://api.groq.com/openai/v1").build();
        }
        @Override public LlmToolResponse callWithTools(LlmToolRequest req) {
            if(!"true".equals(System.getenv("PLANNING_LIVE_APPROVED"))||attempts.get()>=callLimit){errors.add("BUDGET_OR_APPROVAL");return LlmToolResponse.unavailable();}
            try {
                List<Map<String,Object>> messages=new ArrayList<>();
                for(LlmMessage m:req.messages()) {
                    Map<String,Object> wire=new LinkedHashMap<>();wire.put("role",m.role());
                    if(m.content()!=null&&m.content().startsWith("##tool_calls##")) {
                        List<Map<String,Object>> calls=new ArrayList<>();
                        for(String encoded:m.content().substring(14).split("\\|")) {
                            String[] parts=encoded.split("~",-1);var d=Base64.getDecoder();
                            calls.add(Map.of("id",new String(d.decode(parts[0]),StandardCharsets.UTF_8),"type","function","function",Map.of(
                                "name",new String(d.decode(parts[1]),StandardCharsets.UTF_8),"arguments",new String(d.decode(parts[2]),StandardCharsets.UTF_8))));
                        }
                        wire.put("tool_calls",calls);
                    }else wire.put("content",m.content());
                    if(m.toolCallId()!=null)wire.put("tool_call_id",m.toolCallId());messages.add(wire);
                }
                attempts.incrementAndGet();
                String body=client.post().uri("/chat/completions").header("Authorization","Bearer "+System.getenv("GROQ_API_KEY"))
                    .body(Map.of("model",model,"messages",messages,"tools",req.tools(),"tool_choice","auto","temperature",0.1,"max_completion_tokens",3000)).retrieve().body(String.class);
                JsonNode root=mapper.readTree(body),message=root.path("choices").path(0).path("message");
                var trace=mapper.createObjectNode(); trace.set("tool_calls",message.path("tool_calls")); responses.add(trace);
                List<LlmToolCall> calls=new ArrayList<>();for(JsonNode call:message.path("tool_calls"))calls.add(new LlmToolCall(call.path("id").asText(),call.path("function").path("name").asText(),call.path("function").path("arguments").asText()));
                return new LlmToolResponse(calls,message.path("content").isTextual()?message.path("content").asText():null,
                    root.path("usage").path("prompt_tokens").asInt(),root.path("usage").path("completion_tokens").asInt(),false);
            }catch(RestClientResponseException e){errors.add("HTTP_"+e.getStatusCode().value());return LlmToolResponse.unavailable();}
            catch(Exception e){errors.add(e.getClass().getSimpleName());return LlmToolResponse.unavailable();}
        }
        @Override public NlpParseResult parseText(String text,String zone,List<String> groups){throw new UnsupportedOperationException();}
        @Override public NlpParseResult parseVoice(byte[] audio,String zone,List<String> groups){throw new UnsupportedOperationException();}
        @Override public String transcribe(byte[] audio){throw new UnsupportedOperationException();}
    }
}
