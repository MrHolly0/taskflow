package ru.taskflow.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.application.AssistantRateLimiter;
import ru.taskflow.assistant.application.planning.*;
import ru.taskflow.nlp.api.*;
import ru.taskflow.task.api.*;
import ru.taskflow.task.api.dto.*;
import ru.taskflow.user.api.UserService;

import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"app.telegram.bot-token=", "app.public-base-url=", "app.assistant.experimental-reasoning-effort=low"})
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PlanningModesIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired ExperimentalAgentLoop planner;
    @Autowired ru.taskflow.assistant.application.AgentLoop direct;
    @Autowired ru.taskflow.assistant.application.ContextBuilder contexts;
    @Autowired TaskService tasks;
    @Autowired UserService users;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockBean NlpGatewayService model;
    @MockBean AssistantRateLimiter limiter;
    @BeforeEach void allow() { when(limiter.allow(any())).thenReturn(true); }
    UUID user() { long id=ThreadLocalRandom.current().nextLong(1000000000L,9000000000L); return users.findOrCreateByTelegram(id,"plan_"+id,"Test","User").id(); }
    TaskResponse task(UUID user,String title,String group,OffsetDateTime deadline) {
        return tasks.create(user,new CreateTaskRequest(title,null,TaskPriority.MEDIUM,deadline,null,group,List.of(),15,TaskSource.MANUAL,null));
    }
    void reply(PlanningMode mode,String batch,String code) throws Exception {
        var args=mode==PlanningMode.BATCH_PLAN?batch:json.writeValueAsString(Map.of("code",code));
        when(model.callWithTools(any())).thenReturn(new LlmToolResponse(List.of(new LlmToolCall("p",mode==PlanningMode.BATCH_PLAN?"submit_plan":"execute_code",args)),null,123,45,false));
    }
    @ParameterizedTest @EnumSource(value=PlanningMode.class,names={"BATCH_PLAN","CODE_MODE"})
    void findsTaskOutsideInitialWindowWithoutWriting(PlanningMode mode) throws Exception {
        UUID user=user(); var target=task(user,"Проверить приложение QZX","Учёба",null);
        for(int i=0;i<85;i++) task(user,"Обычная задача "+i,"Работа",OffsetDateTime.now().minusDays(2));
        reply(mode,"{\"steps\":[{\"select\":{\"query\":\"QZX\"},\"action\":{\"type\":\"complete\"}}]}",
            "const t=await api.findTasks({query:'QZX'});return {actions:t.map(x=>({type:'complete',task_ref:x.ref}))};");
        var outcome=planner.run(mode,user,"Завершил проверку приложения QZX",ZoneId.of("Europe/Moscow"),AssistantEntryPoint.CHAT);
        assertThat(outcome.actions()).hasSize(1);
        assertThat(outcome.actions().getFirst().targetTaskId()).isEqualTo(target.id());
        assertThat(tasks.findById(user,target.id()).status()).isEqualTo(TaskStatus.TODO);
        assertThat(outcome.inputTokens()).isEqualTo(123);
        assertThat(outcome.passes()).isEqualTo(1);
        var request=org.mockito.ArgumentCaptor.forClass(LlmToolRequest.class);verify(model).callWithTools(request.capture());
        assertThat(request.getValue().messages().toString()).doesNotContain("Обычная задача 84", "Сейчас активных задач нет").contains("задачи-кандидаты (неполный список");
    }
    @ParameterizedTest @EnumSource(value=PlanningMode.class,names={"BATCH_PLAN","CODE_MODE"})
    void expandsGroupSelectionAndKeepsOtherUsersPrivate(PlanningMode mode) throws Exception {
        UUID user=user(), other=user(); var first=task(user,"Сдать отчёт","Учёба",OffsetDateTime.now().minusDays(1));
        var second=task(user,"Проверить расчёты","Учёба",OffsetDateTime.now().minusDays(1));
        task(other,"Чужая задача","Учёба",OffsetDateTime.now().minusDays(1));
        task(user,"Будущая задача","Учёба",OffsetDateTime.now().plusDays(2));
        reply(mode,"{\"steps\":[{\"select\":{\"group\":\"Учёба\",\"overdue\":true,\"all\":true},\"action\":{\"type\":\"cancel\"}}]}",
            "const t=await api.findTasks({group:'Учёба',overdue:true,all:true}); return {actions:t.map(x=>({type:'cancel',task_ref:x.ref}))};");
        var result=planner.run(mode,user,"Отмени все просроченные задачи из группы Учёба",ZoneId.of("Europe/Moscow"),AssistantEntryPoint.CHAT);
        assertThat(result.actions()).extracting(a->a.targetTaskId()).containsExactlyInAnyOrder(first.id(),second.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=? AND status='CANCELLED'",Integer.class,user)).isZero();
    }
    @ParameterizedTest @EnumSource(value=PlanningMode.class,names={"BATCH_PLAN","CODE_MODE"})
    void ambiguityAndOversizedSelectionsNeverApplyPartialChanges(PlanningMode mode) throws Exception {
        UUID user=user();for(int i=0;i<2;i++)task(user,"Отчёт "+i,"Учёба",null);
        reply(mode,"{\"steps\":[{\"select\":{\"query\":\"Отчёт\"},\"action\":{\"type\":\"complete\"}}]}",
            "const t=await api.findTasks({query:'Отчёт'});return {actions:t.map(x=>({type:'complete',task_ref:x.ref}))};");
        var result=planner.run(mode,user,"Закончил отчёт",ZoneId.of("Europe/Moscow"),AssistantEntryPoint.CHAT);
        assertThat(result.actions()).isEmpty();assertThat(result.clarification()).isNotBlank();
        for(int i=0;i<20;i++)task(user,"Отчёт большой "+i,"Учёба",null);
        reply(mode,"{\"steps\":[{\"select\":{\"query\":\"Отчёт\",\"all\":true},\"action\":{\"type\":\"complete\"}}]}",
            "try {await api.findTasks({query:'Отчёт',all:true})}catch(e){}return {actions:[{type:'create',title:'Скрыть ошибку'}]};");
        result=planner.run(mode,user,"Заверши все отчёты",ZoneId.of("Europe/Moscow"),AssistantEntryPoint.CHAT);
        assertThat(result.actions()).isEmpty();assertThat(result.rejections()).contains("SELECTION_LIMIT");
    }
    @Test void inventedReferencesAndUnsafeCodeAreRejected() throws Exception {
        UUID user=user();task(user,"Своя задача",null,null);
        reply(PlanningMode.CODE_MODE,"{}","return {actions:[{type:'complete',task_ref:'T999'}]};");
        assertThat(planner.run(PlanningMode.CODE_MODE,user,"Завершить задачу",ZoneId.of("UTC"),AssistantEntryPoint.CHAT).rejections()).contains("UNISSUED_REFERENCE");
        reply(PlanningMode.CODE_MODE,"{}","return process.env;");
        assertThat(planner.run(PlanningMode.CODE_MODE,user,"Проверить задачу",ZoneId.of("UTC"),AssistantEntryPoint.CHAT).actions()).isEmpty();
    }
    @ParameterizedTest @EnumSource(value=PlanningMode.class,names={"BATCH_PLAN","CODE_MODE"})
    void clarificationAndDeclineKeepExistingControlFlow(PlanningMode mode) throws Exception {
        UUID user=user();reply(mode,"{\"clarification\":\"Какую встречу?\"}","return {clarification:'Какую встречу?'};");
        var result=planner.run(mode,user,"Перенеси встречу",ZoneId.of("UTC"),AssistantEntryPoint.CHAT);
        assertThat(result.clarification()).isEqualTo("Какую встречу?");
        reply(mode,"{\"decline\":{\"reason\":\"chitchat\",\"answer\":\"Пожалуйста\"}}","return {decline:{reason:'chitchat',answer:'Пожалуйста'}};");
        assertThat(planner.run(mode,user,"Спасибо",ZoneId.of("UTC"),AssistantEntryPoint.CHAT).isDeclined()).isTrue();
    }
    @Test void codeCanBranchOnEmptySearchWithoutWriting() throws Exception {
        UUID user=user();reply(PlanningMode.CODE_MODE,"{}",
            "const found=await api.findTasks({query:'Новая задача'}); return found.length ? {clarification:'Уже есть'} : {actions:[{type:'create',title:'Новая задача'}]};");
        var result=planner.run(PlanningMode.CODE_MODE,user,"Если такой задачи нет, добавь новую задачу",ZoneId.of("UTC"),AssistantEntryPoint.CHAT);
        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().type()).isEqualTo(AssistantActionType.CREATE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?",Integer.class,user)).isZero();
    }
    @Test void repairsSyntaxOnceAndCountsBothModelPasses() throws Exception {
        UUID user=user();
        when(model.callWithTools(any())).thenReturn(
            new LlmToolResponse(List.of(new LlmToolCall("p1","execute_code",json.writeValueAsString(Map.of("code","return {")))),null,10,20,false),
            new LlmToolResponse(List.of(new LlmToolCall("p2","execute_code",json.writeValueAsString(Map.of("code","return {actions:[{type:'create',title:'Купить блокнот'}]};")))),null,30,40,false));
        var result=planner.run(PlanningMode.CODE_MODE,user,"Добавь купить блокнот",ZoneId.of("UTC"),AssistantEntryPoint.CHAT);
        assertThat(result.actions()).hasSize(1);assertThat(result.passes()).isEqualTo(2);
        assertThat(result.inputTokens()).isEqualTo(40);assertThat(result.outputTokens()).isEqualTo(60);
        verify(model,times(2)).callWithTools(any());
    }
    @ParameterizedTest @EnumSource(value=PlanningMode.class,names={"BATCH_PLAN","CODE_MODE"})
    void selectorsCannotOverrideTheCurrentUser(PlanningMode mode) throws Exception {
        UUID user=user();
        reply(mode,"{\"steps\":[{\"select\":{\"userId\":\"foreign\"},\"action\":{\"type\":\"complete\"}}]}",
            "return {actions:(await api.findTasks({userId:'foreign'})).map(t=>({type:'complete',task_ref:t.ref}))};");
        var result=planner.run(mode,user,"Заверши задачу",ZoneId.of("UTC"),AssistantEntryPoint.CHAT);
        assertThat(result.actions()).isEmpty();assertThat(result.clarification()).isNotBlank();
    }
    @Test void compareRequestSizeWithFixedModelOutputs() throws Exception {
        UUID user=user();var target=task(user,"Подготовить демонстрацию",null,OffsetDateTime.now().minusDays(100));
        for(int i=0;i<100;i++)task(user,"Пункт рабочего списка номер "+i,"Работа",OffsetDateTime.now().minusDays(20));
        String ref=contexts.build(user).refs().entrySet().stream().filter(e->e.getValue().equals(target.id())).findFirst().orElseThrow().getKey();
        List<Map<String,Object>> rows=new ArrayList<>();
        for(PlanningMode mode:PlanningMode.values()) {
            reset(model);
            String name=mode==PlanningMode.DIRECT?"propose_actions":mode==PlanningMode.BATCH_PLAN?"submit_plan":"execute_code";
            String args=mode==PlanningMode.DIRECT?json.writeValueAsString(Map.of("actions",List.of(Map.of("type","complete","task_ref",ref))))
                :mode==PlanningMode.BATCH_PLAN?"{\"steps\":[{\"select\":{\"query\":\"демонстрацию\"},\"action\":{\"type\":\"complete\"}}]}"
                :json.writeValueAsString(Map.of("code","const t=await api.findTasks({query:'демонстрацию'});return {actions:t.map(x=>({type:'complete',task_ref:x.ref}))};"));
            when(model.callWithTools(any())).thenReturn(new LlmToolResponse(List.of(new LlmToolCall("p",name,args)),null,0,0,false));
            long start=System.nanoTime();
            var out=mode==PlanningMode.DIRECT?direct.run(user,"Заверши подготовку демонстрации",ZoneId.of("Europe/Moscow"),AssistantEntryPoint.CHAT)
                :planner.run(mode,user,"Заверши подготовку демонстрации",ZoneId.of("Europe/Moscow"),AssistantEntryPoint.CHAT);
            var captured=org.mockito.ArgumentCaptor.forClass(LlmToolRequest.class);verify(model).callWithTools(captured.capture());
            assertThat(out.actions()).hasSize(1);assertThat(out.actions().getFirst().targetTaskId()).isEqualTo(target.id());
            rows.add(Map.of("mode",mode.name(),"requestUtf8Bytes",json.writeValueAsBytes(captured.getValue()).length,
                "executionMs",(System.nanoTime()-start)/1000000,"passes",out.passes(),"correct",true));
        }
        var path=java.nio.file.Path.of("build/reports/planning-offline.json");java.nio.file.Files.createDirectories(path.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of("kind","fixed-model-output; bytes are not tokens", "measurements",rows));
    }
    @Test void sqlLikeMetacharactersAreLiteralAndEmptySelectionIsBounded() {
        UUID user=user();task(user,"100% тест",null,null);task(user,"100 других",null,null);
        var selection=tasks.selectForAssistant(user,new AssistantTaskSelector("100%",null,null,null,false,null,null,false));
        assertThat(selection.tasks()).extracting(TaskResponse::title).containsExactly("100% тест");
        assertThat(tasks.selectForAssistant(user,new AssistantTaskSelector("отсутствует",null,null,null,false,null,null,false)).tasks()).isEmpty();
    }
    @Test
    void replaysRecordedFunctionProgramsWithoutCallingExternalModel() throws Exception {
        UUID user = user();
        var document = task(user, "Отправить документы в деканат", "Личное", null);
        var report = task(user, "Подготовить отчёт для кафедры", "Учёба", OffsetDateTime.now().minusDays(1));
        var review = task(user, "Проверить расчёты диплома", "Учёба", OffsetDateTime.now().minusDays(1));
        try (var resource = getClass().getResourceAsStream("/planning-recorded-programs.json")) {
            for (var recorded : json.readTree(resource)) {
                reply(PlanningMode.CODE_MODE, "", recorded.path("code").asText());
                boolean complete = "COMPLETE".equals(recorded.path("type").asText());
                var out = planner.run(PlanningMode.CODE_MODE, user,
                        complete ? "Отметь выполненной задачу отправить документы в деканат"
                                : "Перенеси все просроченные задачи группы Учёба на 15 ноября 2030 года в 18:00",
                        ZoneId.of("Europe/Moscow"), AssistantEntryPoint.CHAT);
                assertThat(out.rejections()).isEmpty();
                assertThat(out.actions()).hasSize(recorded.path("count").asInt());
                assertThat(out.actions()).allMatch(a -> a.type().name().equals(recorded.path("type").asText()));
                assertThat(out.actions()).extracting(a -> a.targetTaskId()).containsExactlyInAnyOrderElementsOf(
                        complete ? Set.of(document.id()) : Set.of(report.id(), review.id()));
            }
        }
        assertThat(tasks.findById(user, document.id()).status()).isEqualTo(TaskStatus.TODO);
        assertThat(tasks.findById(user, report.id()).deadline()).isBefore(OffsetDateTime.now());
    }

    @ParameterizedTest @EnumSource(value=PlanningMode.class,names={"BATCH_PLAN","CODE_MODE"})
    void usesIssuedCandidatesWithoutAnExtraSearch(PlanningMode mode) throws Exception {
        UUID user = user();
        var task = task(user, "Встреча с руководителем", "Учёба", null);
        String ref = contexts.build(user).refs().entrySet().stream().filter(e -> e.getValue().equals(task.id()))
                .findFirst().orElseThrow().getKey();
        reply(mode, json.writeValueAsString(Map.of("steps", List.of(Map.of("select", Map.of("ref", ref),
                        "action", Map.of("type", "complete"))))),
                "return {actions:api.tasks.map(t=>({type:'complete',task_ref:t.ref}))};");
        var outcome = planner.run(mode, user, "Встреча с руководителем завершена",
                ZoneId.of("Europe/Moscow"), AssistantEntryPoint.CHAT);
        assertThat(outcome.rejections()).isEmpty();
        assertThat(outcome.actions()).extracting(a -> a.targetTaskId()).containsExactly(task.id());
    }

    @Test
    void repairsReminderWithoutTargetAsANewTaskWithReminder() throws Exception {
        UUID user = user();
        var at = OffsetDateTime.now().plusDays(1).withNano(0).toString();
        when(model.callWithTools(any())).thenReturn(
                new LlmToolResponse(List.of(new LlmToolCall("p1", "execute_code",
                        json.writeValueAsString(Map.of("code", "return {actions:[{type:'remind',title:'Проверить почту',reminder_at:'" + at + "'}]};")))), null, 10, 20, false),
                new LlmToolResponse(List.of(new LlmToolCall("p2", "execute_code",
                        json.writeValueAsString(Map.of("code", "return {actions:[{type:'create',title:'Проверить почту',reminder_at:'" + at + "'}]};")))), null, 30, 40, false));
        var outcome = planner.run(PlanningMode.CODE_MODE, user, "Напомни завтра проверить почту",
                ZoneId.of("Europe/Moscow"), AssistantEntryPoint.CHAT);
        assertThat(outcome.rejections()).isEmpty();
        assertThat(outcome.actions()).singleElement().satisfies(action -> {
            assertThat(action.type()).isEqualTo(AssistantActionType.CREATE);
            assertThat(action.payload()).containsEntry("reminder_at", at);
        });
        assertThat(outcome.passes()).isEqualTo(2);
        assertThat(outcome.inputTokens()).isEqualTo(40);
        var requests = org.mockito.ArgumentCaptor.forClass(LlmToolRequest.class);
        verify(model, times(2)).callWithTools(requests.capture());
        assertThat(requests.getAllValues()).extracting(LlmToolRequest::reasoningEffort).containsExactly("low", "medium");
    }

    @Test void acceptsJsonResponseWithoutToolCalls() throws Exception {
        UUID user = user();
        var target = task(user, "Проверить документы", "Учёба", null);
        String ref = contexts.build(user).refs().entrySet().stream().filter(e -> e.getValue().equals(target.id()))
                .findFirst().orElseThrow().getKey();
        String plan = json.writeValueAsString(Map.of("steps", List.of(Map.of(
                "select", Map.of("ref", ref), "action", Map.of("type", "complete")))));
        when(model.callWithTools(any())).thenReturn(new LlmToolResponse(List.of(), plan, 10, 20, false));
        var outcome = planner.run(PlanningMode.BATCH_PLAN, user, "Документы проверены",
                ZoneId.of("Europe/Moscow"), AssistantEntryPoint.CHAT);
        assertThat(outcome.actions()).extracting(a -> a.targetTaskId()).containsExactly(target.id());
        var request = org.mockito.ArgumentCaptor.forClass(LlmToolRequest.class);
        verify(model).callWithTools(request.capture());
        assertThat(request.getValue().jsonOutput()).isTrue();
        assertThat(request.getValue().requiredTool()).isNull();
    }

    @ParameterizedTest @EnumSource(value=PlanningMode.class,names={"BATCH_PLAN","CODE_MODE"})
    void explicitCancellationDoesNotCreateAnUnrequestedAlternative(PlanningMode mode) throws Exception {
        UUID user = user();
        var target = task(user, "Сходить в спортзал", null, null);
        String ref = contexts.build(user).refs().entrySet().stream().filter(e -> e.getValue().equals(target.id()))
                .findFirst().orElseThrow().getKey();
        reply(mode, json.writeValueAsString(Map.of("steps", List.of(Map.of("select", Map.of("ref", ref),
                        "action", Map.of("type", "cancel"))))),
                "return {actions:[{type:'cancel',task_ref:'" + ref + "'}]};");
        var outcome = planner.run(mode, user, "отмени поход в спортзал",
                ZoneId.of("Europe/Moscow"), AssistantEntryPoint.CHAT);
        assertThat(outcome.actions()).singleElement().satisfies(action -> {
            assertThat(action.type()).isEqualTo(AssistantActionType.CANCEL);
            assertThat(action.targetTaskId()).isEqualTo(target.id());
        });
        assertThat(outcome.ambiguous()).isFalse();
    }

    @ParameterizedTest @EnumSource(value=PlanningMode.class,names={"BATCH_PLAN","CODE_MODE"})
    void retainsAnExplicitChoiceWithoutAddingOtherActions(PlanningMode mode) throws Exception {
        UUID user = user();
        var target = task(user, "Кино с другом", null, null);
        String ref = contexts.build(user).refs().entrySet().stream().filter(e -> e.getValue().equals(target.id()))
                .findFirst().orElseThrow().getKey();
        reply(mode, json.writeValueAsString(Map.of("steps", List.of(
                        Map.of("select", Map.of("ref", ref), "action", Map.of("type", "complete", "ambiguous_reason", "Два прочтения")),
                        Map.of("action", Map.of("type", "create", "title", "закрыть кино", "ambiguous_reason", "Два прочтения"))))),
                "return {actions:[{type:'complete',task_ref:'" + ref + "',ambiguous_reason:'Два прочтения'},"
                        + "{type:'create',title:'закрыть кино',ambiguous_reason:'Два прочтения'}]};");
        var outcome = planner.run(mode, user, "закрыть кино", ZoneId.of("Europe/Moscow"), AssistantEntryPoint.CHAT);
        assertThat(outcome.ambiguous()).isTrue();
        assertThat(outcome.actions()).hasSize(2);
        assertThat(outcome.actions().get(0).accepted()).isTrue();
        assertThat(outcome.actions().get(1).accepted()).isFalse();
    }

    @Test void jsonRepairsRejectedFieldsWithoutLosingOtherActions() {
        UUID user = user();
        String invalid = "{\"steps\":[{\"action\":{\"type\":\"create\",\"title\":\"Купить блокнот\"}},"
                + "{\"action\":{\"type\":\"create\",\"title\":\"Посетить выставку\",\"deadline\":\"2030-11-15\"}}]}";
        String fixed = invalid.replace("2030-11-15\"", "2030-11-15T09:00:00+03:00\"");
        when(model.callWithTools(any())).thenReturn(
                new LlmToolResponse(List.of(), invalid, 10, 20, false),
                new LlmToolResponse(List.of(), fixed, 30, 40, false));
        var outcome = planner.run(PlanningMode.BATCH_PLAN, user, "Купить блокнот и посетить выставку 15 ноября 2030",
                ZoneId.of("Europe/Moscow"), AssistantEntryPoint.CHAT);
        assertThat(outcome.rejections()).isEmpty();
        assertThat(outcome.actions()).hasSize(2);
        assertThat(outcome.passes()).isEqualTo(2);
        assertThat(outcome.inputTokens()).isEqualTo(40);
        assertThat(outcome.outputTokens()).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?", Integer.class, user)).isZero();
    }

    @Test void jsonDoesNotReturnAPartialPlanAfterTwoInvalidAttempts() {
        UUID user = user();
        String invalid = "{\"steps\":[{\"action\":{\"type\":\"create\",\"title\":\"Купить блокнот\"}},"
                + "{\"action\":{\"type\":\"create\",\"title\":\"Выставка\",\"deadline\":\"не дата\"}}]}";
        when(model.callWithTools(any())).thenReturn(new LlmToolResponse(List.of(), invalid, 10, 20, false));
        var outcome = planner.run(PlanningMode.BATCH_PLAN, user, "Купить блокнот и посетить выставку",
                ZoneId.of("UTC"), AssistantEntryPoint.CHAT);
        assertThat(outcome.actions()).isEmpty();
        assertThat(outcome.rejections()).contains("ACTION_VALIDATION");
        verify(model, times(2)).callWithTools(any());
    }

    @Test void jsonCanRepairAnEmptySelectionWithoutKeepingThePreviousFailure() {
        UUID user = user();
        var target = task(user, "Проверить договор", null, null);
        when(model.callWithTools(any())).thenReturn(
                new LlmToolResponse(List.of(), "{\"steps\":[{\"select\":{\"query\":\"документ\"},\"action\":{\"type\":\"complete\"}}]}", 10, 20, false),
                new LlmToolResponse(List.of(), "{\"steps\":[{\"select\":{\"query\":\"договор\"},\"action\":{\"type\":\"complete\"}}]}", 30, 40, false));
        var outcome = planner.run(PlanningMode.BATCH_PLAN, user, "Договор проверен",
                ZoneId.of("UTC"), AssistantEntryPoint.CHAT);
        assertThat(outcome.rejections()).isEmpty();
        assertThat(outcome.actions()).extracting(a -> a.targetTaskId()).containsExactly(target.id());
        assertThat(tasks.findById(user, target.id()).status()).isEqualTo(TaskStatus.TODO);
    }

    @Test void jsonRepairKeepsTheOriginalSearchBudget() {
        UUID user = user();
        task(user, "Проверить договор", null, null);
        String step = "{\"select\":{\"query\":\"договор\"},\"action\":{\"type\":\"complete\"}}";
        String missing = "{\"select\":{\"query\":\"нет такой задачи\"},\"action\":{\"type\":\"complete\"}}";
        String first = "{\"steps\":[" + String.join(",", Collections.nCopies(7, step)) + "," + missing + "]}";
        when(model.callWithTools(any())).thenReturn(
                new LlmToolResponse(List.of(), first, 10, 20, false),
                new LlmToolResponse(List.of(), "{\"steps\":[" + step + "]}", 30, 40, false));
        var outcome = planner.run(PlanningMode.BATCH_PLAN, user, "Заверши проверку договора",
                ZoneId.of("UTC"), AssistantEntryPoint.CHAT);
        assertThat(outcome.actions()).isEmpty();
        assertThat(outcome.rejections()).contains("TOOL_LIMIT");
        assertThat(tasks.findById(user, tasks.selectForAssistant(user,
                new AssistantTaskSelector("договор", null, null, null, false, null, null, false)).tasks().getFirst().id()).status())
                .isEqualTo(TaskStatus.TODO);
    }
}
