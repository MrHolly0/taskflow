package ru.taskflow.assistant.api.dto;

import ru.taskflow.assistant.api.DeclineReason;
import ru.taskflow.assistant.api.ProposalStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Proposal(
        UUID id,
        String shortCode,
        UUID userId,
        ProposalStatus status,
        String sourceText,
        String clarification,
        List<ProposedAction> actions,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        boolean exclusive,
        String ambiguityReason,
        List<String> rejections,
        int inputTokens,
        int outputTokens,
        long totalLatencyMs,
        long firstPassLatencyMs,
        long secondPassLatencyMs,
        int modelPasses,
        DeclineReason declineReason
) {
    // Совместимость со старыми вызовами: до mark_ambiguous предложение не могло
    // быть взаимоисключающим набором альтернатив, до починки учёта токенов и
    // задержек по этапам, до no_action — причины отказа — этих полей тоже не было.
    public Proposal(UUID id, String shortCode, UUID userId, ProposalStatus status, String sourceText,
                     String clarification, List<ProposedAction> actions, OffsetDateTime createdAt,
                     OffsetDateTime expiresAt) {
        this(id, shortCode, userId, status, sourceText, clarification, actions, createdAt, expiresAt, false, null,
                List.of(), 0, 0, 0, 0, 0, 1, null);
    }

    // Промежуточная совместимость: до no_action отдельного отказа от действия не было.
    public Proposal(UUID id, String shortCode, UUID userId, ProposalStatus status, String sourceText,
                     String clarification, List<ProposedAction> actions, OffsetDateTime createdAt,
                     OffsetDateTime expiresAt, boolean exclusive, String ambiguityReason, List<String> rejections,
                     int inputTokens, int outputTokens, long totalLatencyMs, long firstPassLatencyMs,
                     long secondPassLatencyMs, int modelPasses) {
        this(id, shortCode, userId, status, sourceText, clarification, actions, createdAt, expiresAt, exclusive,
                ambiguityReason, rejections, inputTokens, outputTokens, totalLatencyMs, firstPassLatencyMs,
                secondPassLatencyMs, modelPasses, null);
    }

    public boolean hasActions() {
        return actions != null && !actions.isEmpty();
    }

    public boolean hasRejections() {
        return rejections != null && !rejections.isEmpty();
    }

    public boolean hasClarification() {
        return clarification != null && !clarification.isBlank();
    }
}
