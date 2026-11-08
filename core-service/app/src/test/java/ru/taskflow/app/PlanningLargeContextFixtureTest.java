package ru.taskflow.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.assistant.application.ContextBuilder;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.user.api.UserService;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.telegram.bot-token=", "app.public-base-url="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlanningLargeContextFixtureTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired TaskService tasks;
    @Autowired UserService users;
    @Autowired ContextBuilder contexts;
    @Autowired JdbcTemplate jdbc;
    @MockBean NlpGatewayService model;

    @Test void everyAccountHasExactSizeAndIntendedVisibilityWithoutModelCalls() {
        var harness = new PlanningLargeContextLiveTest();
        harness.tasks = tasks;harness.users = users;harness.contexts = contexts;harness.jdbc = jdbc;
        LocalDate day = LocalDate.now(PlanningLargeContextCases.ZONE);
        var scenarios = new java.util.ArrayList<>(PlanningLargeContextCases.all(day));
        scenarios.addAll(PlanningLargeContextControlCases.all(day));
        for (int count : new int[]{100, 200}) for (var scenario : scenarios) {
            var fixture = harness.fixture(count, day, scenario);
            assertThat(harness.snapshot(fixture.user(), fixture.other())).isEqualTo(fixture.snapshot());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?", Integer.class, fixture.other())).isEqualTo(1);
            assertThat(fixture.ids()).hasSize(scenario.overflow() ? 34 : 13);
        }
        verifyNoInteractions(model);
    }
}
