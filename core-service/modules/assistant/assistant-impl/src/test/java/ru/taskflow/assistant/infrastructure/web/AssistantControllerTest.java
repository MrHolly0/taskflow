package ru.taskflow.assistant.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.assistant.api.exception.ProposalNotFoundException;
import ru.taskflow.assistant.application.QuickAddPolicy;
import ru.taskflow.shared.security.AuthenticatedUser;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AssistantControllerTest {

    @Mock
    private AssistantService assistantService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private MockMvc mockMvc;

    private final QuickAddPolicy quickAddPolicy = new QuickAddPolicy();

    @BeforeEach
    void setUp() {
        var controller = new AssistantController(assistantService,org.mockito.Mockito.mock(ru.taskflow.task.api.TaskOperationService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(userId, "user"), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void handleMessage_returnsCreatedProposal() throws Exception {
        when(assistantService.handleText(eq(userId), eq("закрой молоко"), eq(AssistantChannel.WEB), eq(AssistantEntryPoint.CHAT)))
                .thenReturn(proposal());

        mockMvc.perform(multipart("/api/v1/assistant/messages").param("text", "закрой молоко"))
                .andExpect(status().isCreated());
    }

    @Test
    void handleMessage_rejectsWhenRateLimited() throws Exception {
        when(assistantService.handleText(any(), any(), any(), any()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        mockMvc.perform(multipart("/api/v1/assistant/messages").param("text", "закрой молоко"))
                .andExpect(status().isTooManyRequests());


    }

    @Test
    void findById_returnsProposal() throws Exception {
        UUID id = UUID.randomUUID();
        when(assistantService.findById(userId, id)).thenReturn(proposal());

        mockMvc.perform(get("/api/v1/assistant/proposals/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    void findById_returnsNotFoundForForeignProposal() throws Exception {
        UUID id = UUID.randomUUID();
        when(assistantService.findById(userId, id)).thenThrow(new ProposalNotFoundException(id));

        mockMvc.perform(get("/api/v1/assistant/proposals/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void setActionAccepted_returnsProposal() throws Exception {
        UUID id = UUID.randomUUID();
        when(assistantService.setActionAccepted(userId, id, 1, false)).thenReturn(proposal());

        mockMvc.perform(patch("/api/v1/assistant/proposals/{id}/actions/{ordinal}", id, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    void setActionAccepted_rejectsInvalidBody() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/assistant/proposals/{id}/actions/{ordinal}", id, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());


    }

    @Test
    void selectAlternative_returnsProposal() throws Exception {
        UUID id = UUID.randomUUID();
        when(assistantService.selectAlternative(userId, id, 2)).thenReturn(proposal());

        mockMvc.perform(post("/api/v1/assistant/proposals/{id}/actions/{ordinal}/select", id, 2))
                .andExpect(status().isOk());
    }

    @Test
    void apply_returnsApplyResult() throws Exception {
        UUID id = UUID.randomUUID();
        when(assistantService.apply(userId, id))
                .thenReturn(new ApplyResult(ProposalStatus.APPLIED, 1, 1, List.of()));

        mockMvc.perform(post("/api/v1/assistant/proposals/{id}/apply", id))
                .andExpect(status().isOk());
    }

    @Test
    void reject_returnsNoContent() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/assistant/proposals/{id}/reject", id))
                .andExpect(status().isNoContent());

        verify(assistantService).reject(userId, id);
    }

    @Test
    void quick_autoAppliesCreateOnlyProposal() throws Exception {
        Proposal proposal = proposalWithActions(ProposalStatus.PENDING, null,
                new ProposedAction(1, AssistantActionType.CREATE, null, Map.of(), "создать «купить хлеб»", true));
        when(assistantService.handleText(eq(userId), eq("купи хлеб"), eq(AssistantChannel.WEB), eq(AssistantEntryPoint.QUICK_ADD)))
                .thenReturn(proposal);
        ApplyResult applyResult = new ApplyResult(ProposalStatus.APPLIED, 1, 1, List.of());
        when(assistantService.apply(userId, proposal.id())).thenReturn(applyResult);

        mockMvc.perform(multipart("/api/v1/assistant/quick").param("text", "купи хлеб"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied.status").value("APPLIED"));

        verify(assistantService).apply(userId, proposal.id());
    }

    @Test
    void quick_returnsForConfirmationWhenActionTouchesExistingTask() throws Exception {
        Proposal proposal = proposalWithActions(ProposalStatus.PENDING, null,
                new ProposedAction(1, AssistantActionType.COMPLETE, UUID.randomUUID(), Map.of(), "закрыть «молоко»", true));
        when(assistantService.handleText(eq(userId), eq("закрой молоко"), eq(AssistantChannel.WEB), eq(AssistantEntryPoint.QUICK_ADD)))
                .thenReturn(proposal);

        mockMvc.perform(multipart("/api/v1/assistant/quick").param("text", "закрой молоко"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied").doesNotExist());

        verify(assistantService, never()).apply(any(), any());
    }

    @Test
    void quick_doesNotAutoApplyDegradedProposal() throws Exception {
        Proposal degraded = new Proposal(null, null, userId, ProposalStatus.FAILED,
                "закрой молоко", "не удалось разобрать сообщение", List.of(),
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(24));
        when(assistantService.handleText(eq(userId), eq("закрой молоко"), eq(AssistantChannel.WEB), eq(AssistantEntryPoint.QUICK_ADD)))
                .thenReturn(degraded);

        mockMvc.perform(multipart("/api/v1/assistant/quick").param("text", "закрой молоко"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied").doesNotExist());

        verify(assistantService, never()).apply(any(), any());
    }

    @Test
    void quickKeepsIncompleteOrAmbiguousCreateForConfirmation() throws Exception {
        var action = new ProposedAction(1, AssistantActionType.CREATE, null, Map.of(), "Купить хлеб", true);
        for (int variant = 0; variant < 4; variant++) {
            var value = new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                    "купи хлеб", null,
                    List.of(variant == 3 ? new ProposedAction(1, AssistantActionType.CREATE, null, Map.of(), "Купить хлеб", false) : action),
                    OffsetDateTime.now(), OffsetDateTime.now().plusHours(24), variant == 0,
                    variant == 1 ? "Нужно уточнить" : null, variant == 2 ? List.of("Вторая часть не разобрана") : List.of(),
                    0, 0, 0, 0, 0, 1);
            when(assistantService.handleText(userId, "купи хлеб", AssistantChannel.WEB, AssistantEntryPoint.QUICK_ADD))
                    .thenReturn(value);
            mockMvc.perform(multipart("/api/v1/assistant/quick").param("text", "купи хлеб"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.applied").doesNotExist());
        }
        verify(assistantService, never()).apply(any(), any());
    }

    @Test
    void quick_rejectsWhenRateLimited() throws Exception {
        when(assistantService.handleText(any(), any(), any(), any()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        mockMvc.perform(multipart("/api/v1/assistant/quick").param("text", "купи хлеб"))
                .andExpect(status().isTooManyRequests());


    }

    private Proposal proposal() {
        return new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "закрой молоко", null, List.of(), OffsetDateTime.now(), OffsetDateTime.now().plusHours(24));
    }

    private Proposal proposalWithActions(ProposalStatus status, String clarification, ProposedAction... actions) {
        return new Proposal(UUID.randomUUID(), "CODE1234", userId, status,
                "любой текст", clarification, List.of(actions), OffsetDateTime.now(), OffsetDateTime.now().plusHours(24));
    }
}
