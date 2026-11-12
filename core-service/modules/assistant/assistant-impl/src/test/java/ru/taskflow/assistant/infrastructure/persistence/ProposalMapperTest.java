package ru.taskflow.assistant.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.ProposalStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalMapperTest {

    private final ru.taskflow.user.api.UserService users=org.mockito.Mockito.mock(ru.taskflow.user.api.UserService.class);
    private final ProposalMapper mapper = new ProposalMapper(new com.fasterxml.jackson.databind.ObjectMapper(),users,new ru.taskflow.assistant.application.SummaryRenderer());
    @BeforeEach void timezone(){org.mockito.Mockito.when(users.getTimezone(org.mockito.ArgumentMatchers.any())).thenReturn(java.time.ZoneId.of("Europe/Moscow"));}

    @Test
    void toDto_copiesEveryScalarField() {
        var entity = entity();

        var dto = mapper.toDto(entity);

        assertThat(dto.id()).isEqualTo(entity.getId());
        assertThat(dto.shortCode()).isEqualTo("ABC23456");
        assertThat(dto.userId()).isEqualTo(entity.getUserId());
        assertThat(dto.status()).isEqualTo(ProposalStatus.PENDING);
        assertThat(dto.sourceText()).isEqualTo("в магазин сходил");
        assertThat(dto.clarification()).isEqualTo("какая именно задача?");
        assertThat(dto.createdAt()).isEqualTo(entity.getCreatedAt());
        assertThat(dto.expiresAt()).isEqualTo(entity.getExpiresAt());
    }

    @Test
    void toDto_mapsActionsInOrdinalOrder() {
        var entity = entity();
        entity.addAction(action(2, "COMPLETE", "Закрыть — молоко", true));
        entity.addAction(action(1, "CREATE", "Создать — хлеб", false));

        var dto = mapper.toDto(entity);

        assertThat(dto.actions()).extracting("ordinal").containsExactly(1, 2);
        assertThat(dto.actions().getFirst().type()).isEqualTo(AssistantActionType.CREATE);
        assertThat(dto.actions().getFirst().accepted()).isFalse();
        assertThat(dto.actions().get(1).accepted()).isTrue();
    }

    @Test
    void toDto_parsesPayloadJson() {
        var entity = entity();
        var a = action(1, "CREATE", "Создать — хлеб", true);
        a.setPayload("{\"title\":\"хлеб\",\"priority\":\"HIGH\"}");
        entity.addAction(a);

        var dto = mapper.toDto(entity);

        assertThat(dto.actions().getFirst().payload()).containsEntry("title", "хлеб");
    }

    @Test
    void toDto_toleratesBrokenPayload() {
        var entity = entity();
        var a = action(1, "CREATE", "Создать — хлеб", true);
        a.setPayload("{это не json");
        entity.addAction(a);

        var dto = mapper.toDto(entity);

        assertThat(dto.actions().getFirst().payload()).isEmpty();
    }

    @Test
    void toDto_handlesEmptyActions() {
        var dto = mapper.toDto(entity());

        assertThat(dto.actions()).isEmpty();
        assertThat(dto.hasActions()).isFalse();
    }

    @Test
    void toDto_copiesAmbiguityFields() {
        var entity = entity();
        entity.setExclusive(true);
        entity.setAmbiguityReason("не понял, про какое кино речь");

        var dto = mapper.toDto(entity);

        assertThat(dto.exclusive()).isTrue();
        assertThat(dto.ambiguityReason()).isEqualTo("не понял, про какое кино речь");
    }

    @Test
    void toDto_defaultsExclusiveToFalse() {
        var dto = mapper.toDto(entity());

        assertThat(dto.exclusive()).isFalse();
        assertThat(dto.ambiguityReason()).isNull();
    }

    @Test
    void toDto_parsesRejectionsJson() {
        var entity = entity();
        entity.setRejections("[\"смена названия отклонена: текущее название задачи не упомянуто в реплике (Сдать отчёт)\"]");

        var dto = mapper.toDto(entity);

        assertThat(dto.rejections()).containsExactly(
                "смена названия отклонена: текущее название задачи не упомянуто в реплике (Сдать отчёт)");
    }

    @Test
    void toDto_defaultsRejectionsToEmptyList() {
        var dto = mapper.toDto(entity());

        assertThat(dto.rejections()).isEmpty();
        assertThat(dto.hasRejections()).isFalse();
    }

    @Test
    void toDto_copiesTokenUsage() {
        var entity = entity();
        entity.setInputTokens(1234);
        entity.setOutputTokens(567);

        var dto = mapper.toDto(entity);

        assertThat(dto.inputTokens()).isEqualTo(1234);
        assertThat(dto.outputTokens()).isEqualTo(567);
    }

    @Test
    void toDto_copiesTotalLatencyButNotPerStage() {
        var entity = entity();
        entity.setLatencyMs(4200);

        var dto = mapper.toDto(entity);

        assertThat(dto.totalLatencyMs()).isEqualTo(4200);
        // По проходам — не столбцы, а живут только в AgentOutcome сразу после
        // run(); AssistantServiceImpl накладывает их поверх этого dto отдельно.
        assertThat(dto.firstPassLatencyMs()).isZero();
        assertThat(dto.secondPassLatencyMs()).isZero();
    }

    @Test
    void toDto_copiesModelPasses() {
        var entity = entity();
        entity.setLlmPasses(2);

        var dto = mapper.toDto(entity);

        assertThat(dto.modelPasses()).isEqualTo(2);
    }

    @Test
    void toDto_toleratesBrokenRejectionsJson() {
        var entity = entity();
        entity.setRejections("{это не json");

        var dto = mapper.toDto(entity);

        assertThat(dto.rejections()).isEmpty();
    }

    private ProposalJpaEntity entity() {
        var p = new ProposalJpaEntity();
        p.setId(UUID.randomUUID());
        p.setShortCode("ABC23456");
        p.setUserId(UUID.randomUUID());
        p.setSourceChannel("TELEGRAM");
        p.setInputKind("TEXT");
        p.setSourceText("в магазин сходил");
        p.setStatus("PENDING");
        p.setClarification("какая именно задача?");
        p.setCreatedAt(OffsetDateTime.now());
        p.setExpiresAt(OffsetDateTime.now().plusHours(24));
        return p;
    }

    private ProposalActionJpaEntity action(int ordinal, String type, String summary, boolean accepted) {
        var a = new ProposalActionJpaEntity();
        a.setOrdinal(ordinal);
        a.setType(type);
        a.setSummary(summary);
        a.setAccepted(accepted);
        return a;
    }
}
