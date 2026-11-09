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
        List<PlanningMode> modes = followup ? List.of(PlanningMode.BATCH_PLAN, PlanningMode.CODE_MODE)
                : Arrays.stream(System.getenv().getOrDefault("PLANNING_MODES", "DIRECT,BATCH_PLAN,CODE_MODE").split(","))
                        .map(PlanningMode::valueOf).toList();
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
    @Test void preservesPreparationBeforeDelivery() throws Exception {
        assumeTrue("true".equals(System.getenv("PLANNING_LIVE_APPROVED")));
        assumeTrue(System.getenv("GROQ_API_KEY") != null && !System.getenv("GROQ_API_KEY").isBlank());
        when(limiter.allow(any())).thenReturn(true);
        long telegram = ThreadLocalRandom.current().nextLong(1000000000L, 9000000000L);
        UUID user = users.findOrCreateByTelegram(telegram, "workflow_" + telegram, "Test", "User").id();
        task(user, "Купить блокнот", "Обучение", null);
        task(user, "Заказать бумагу", "Работа", null);
        ZoneId zone = ZoneId.of("Europe/Moscow");
        var rows = new ArrayList<Map<String, Object>>();
        for (int repeat = 0; repeat < 3; repeat++) {
            var result = experimental.run(PlanningMode.BATCH_PLAN, user,
                    "Через неделю мне нужно сдать доклад по физике, нужно на следующей неделе его доделать, а затем сдать",
                    zone, AssistantEntryPoint.CHAT);
            var created = result.actions().stream().filter(action -> action.type() == AssistantActionType.CREATE).toList();
            rows.add(Map.of("actions", json.valueToTree(created), "rejections", result.rejections()));
            Path report = Path.of("build/reports/workflow-planning-live.json");
            Files.createDirectories(report.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of("rows", rows, "httpAttempts", gateway.attempts.get()));
            assertThat(created).hasSize(2);
            var delivery = created.stream().filter(action -> String.valueOf(action.payload().get("title")).toLowerCase(Locale.ROOT).contains("сдат")).findFirst().orElseThrow();
            var preparation = created.stream().filter(action -> action != delivery).findFirst().orElseThrow();
            var due = OffsetDateTime.parse(String.valueOf(delivery.payload().get("deadline")));
            var before = OffsetDateTime.parse(String.valueOf(preparation.payload().get("planned_date")));
            assertThat(due.atZoneSameInstant(zone).toLocalDate()).isEqualTo(LocalDate.now(zone).plusDays(7));
            assertThat(before).isBefore(due);
            assertThat(before.atZoneSameInstant(zone).toLocalDate())
                    .isAfterOrEqualTo(LocalDate.now(zone).with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY)));
            assertThat(created).allSatisfy(action -> assertThat(action.payload().get("group")).isEqualTo("Обучение"));
        }
        Path path = Path.of("build/reports/workflow-planning-live.json");
        Files.createDirectories(path.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), Map.of("rows", rows, "httpAttempts", gateway.attempts.get()));
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
        final int callLimit = Math.min(3000, Integer.parseInt(System.getenv().getOrDefault("PLANNING_LIVE_CALL_LIMIT", "25")));
        final String model=System.getenv().getOrDefault("PLANNING_BENCH_MODEL","openai/gpt-oss-120b");
        final PlanningApiBudget budget = System.getenv("PLANNING_LIVE_BUDGET_FILE") == null ? null
                : new PlanningApiBudget(Path.of(System.getenv("PLANNING_LIVE_BUDGET_FILE")));
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
                if (budget != null && !budget.reserve()) {
                    errors.add("BUDGET_OR_APPROVAL");
                    return LlmToolResponse.unavailable();
                }
                attempts.incrementAndGet();
                Map<String,Object> request=new LinkedHashMap<>(Map.of("model",model,"messages",messages,"tools",req.tools(),
                        "tool_choice","auto","temperature",0.1,"max_completion_tokens",3000));
                if (req.reasoningEffort()!=null) request.put("reasoning_effort",req.reasoningEffort());
                if (req.requiredTool()!=null) request.put("tool_choice",Map.of("type","function","function",Map.of("name",req.requiredTool())));
                if (Boolean.TRUE.equals(req.jsonOutput())) {
                    request.remove("tools"); request.remove("tool_choice");
                    request.put("response_format",Map.of("type","json_object"));
                }
                long before=System.nanoTime();
                var entity=client.post().uri("/chat/completions").header("Authorization","Bearer "+System.getenv("GROQ_API_KEY"))
                    .body(request).retrieve().toEntity(String.class);
                String body=entity.getBody();
                JsonNode root=mapper.readTree(body),message=root.path("choices").path(0).path("message");
                var trace=mapper.createObjectNode();
                trace.set("tool_calls",message.path("tool_calls"));
                if (message.path("content").isTextual()) trace.put("text",message.path("content").asText());
                trace.set("usage",root.path("usage"));
                trace.put("httpMs",(System.nanoTime()-before)/1000000);
                var limits=mapper.createObjectNode();
                for (String header : List.of("x-ratelimit-limit-tokens","x-ratelimit-remaining-tokens","x-ratelimit-reset-tokens"))
                    if (entity.getHeaders().getFirst(header)!=null) limits.put(header,entity.getHeaders().getFirst(header));
                trace.set("rateLimits",limits);
                trace.set("toolChoice",mapper.valueToTree(request.get("tool_choice")));
                trace.put("jsonOutput",Boolean.TRUE.equals(req.jsonOutput()));
                trace.put("reasoningEffort",req.reasoningEffort()==null?"provider-default":req.reasoningEffort());
                responses.add(trace);
                List<LlmToolCall> calls=new ArrayList<>();for(JsonNode call:message.path("tool_calls"))calls.add(new LlmToolCall(call.path("id").asText(),call.path("function").path("name").asText(),call.path("function").path("arguments").asText()));
                return new LlmToolResponse(calls,message.path("content").isTextual()?message.path("content").asText():null,
                    root.path("usage").path("prompt_tokens").asInt(),root.path("usage").path("completion_tokens").asInt(),false);
            }catch(RestClientResponseException e){
                String code="";
                try {
                    var error=mapper.readTree(e.getResponseBodyAsString()).path("error");
                    String candidate=error.path("code").asText("");
                    if (candidate.matches("[A-Za-z0-9_]{1,80}")) code=candidate;
                    var trace=mapper.createObjectNode().put("providerError",code).put("httpStatus",e.getStatusCode().value());
                    String failed=error.path("failed_generation").asText("");
                    if (!failed.isBlank()) trace.put("failedGeneration",failed.substring(0,Math.min(4096,failed.length())));
                    responses.add(trace);
                }catch(Exception ignored){}
                errors.add("HTTP_"+e.getStatusCode().value()+(code.isBlank()?"":":"+code));
                return LlmToolResponse.unavailable();
            }
            catch(Exception e){errors.add(e.getClass().getSimpleName());return LlmToolResponse.unavailable();}
        }
        @Override public NlpParseResult parseText(String text,String zone,List<String> groups){throw new UnsupportedOperationException();}
        @Override public NlpParseResult parseVoice(byte[] audio,String zone,List<String> groups){throw new UnsupportedOperationException();}
        @Override public String transcribe(byte[] audio){throw new UnsupportedOperationException();}
    }
}
