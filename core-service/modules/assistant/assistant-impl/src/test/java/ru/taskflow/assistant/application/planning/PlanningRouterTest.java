package ru.taskflow.assistant.application.planning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.application.AgentLoop;
import java.time.ZoneId;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlanningRouterTest {
    @ParameterizedTest @EnumSource(PlanningMode.class)
    void routesOnlyToTheConfiguredPlanner(PlanningMode mode) {
        var direct=mock(AgentLoop.class);var experimental=mock(ExperimentalAgentLoop.class);
        var router=new PlanningRouter(direct,experimental,mode.name());
        UUID user=UUID.randomUUID();ZoneId zone=ZoneId.of("Europe/Moscow");
        router.run(user,"Запрос",zone,AssistantEntryPoint.CHAT);
        if(mode==PlanningMode.DIRECT){verify(direct).run(user,"Запрос",zone,AssistantEntryPoint.CHAT);verifyNoInteractions(experimental);}
        else{verify(experimental).run(mode,user,"Запрос",zone,AssistantEntryPoint.CHAT);verifyNoInteractions(direct);}
    }
    @Test void invalidModeDoesNotSilentlyEnableExecution() {
        assertThatThrownBy(()->new PlanningRouter(mock(AgentLoop.class),mock(ExperimentalAgentLoop.class),"unknown"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
