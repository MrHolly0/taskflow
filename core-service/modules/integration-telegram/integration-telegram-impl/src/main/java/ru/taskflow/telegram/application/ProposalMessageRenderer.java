package ru.taskflow.telegram.application;

import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.dto.ActionOutcome;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender.InlineButton;

import java.util.ArrayList;
import java.util.List;

/**
 * short_code, а не UUID предложения, потому что callback_data Telegram
 * ограничен 64 байтами — UUID в текстовом виде (36 символов) вместе с
 * префиксом и порядковым номером действия этот запас съедает.
 */
@Component
public class ProposalMessageRenderer {

    public String render(Proposal proposal) {
        if (proposal.hasClarification()) {
            return proposal.clarification();
        }
        if (!proposal.hasActions()) {
            if (proposal.hasRejections()) {
                return "Не нашёл, что предложить по вашему сообщению.\n\n" + renderRejections(proposal);
            }
            return "Не нашёл, что предложить по вашему сообщению.";
        }
        StringBuilder sb = new StringBuilder("Предлагаю:\n\n");
        for (ProposedAction action : proposal.actions()) {
            sb.append(action.accepted() ? '☑' : '☐').append(' ').append(action.summary()).append('\n');
        }
        if (proposal.hasRejections()) {
            sb.append('\n').append(renderRejections(proposal));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Спокойный тон намеренно: это не сбой системы, а объяснение, почему
     * часть сказанного не превратилась в действие — человек и так видит,
     * что действий меньше, чем говорил, без единого слова причины было бы хуже.
     */
    private String renderRejections(Proposal proposal) {
        StringBuilder sb = new StringBuilder("Не учтено:\n");
        for (String rejection : proposal.rejections()) {
            sb.append("· ").append(rejection).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public List<List<InlineButton>> keyboard(Proposal proposal) {
        if (!proposal.hasActions()) {
            return List.of();
        }
        List<List<InlineButton>> rows = new ArrayList<>();
        for (ProposedAction action : proposal.actions()) {
            String mark = action.accepted() ? "☑" : "☐";
            rows.add(List.of(new InlineButton(mark + " " + action.summary(),
                    "p:" + proposal.shortCode() + ":t:" + action.ordinal())));
        }
        rows.add(List.of(
                new InlineButton("Применить", "p:" + proposal.shortCode() + ":a"),
                new InlineButton("Отклонить", "p:" + proposal.shortCode() + ":r")
        ));
        return rows;
    }

    public String renderApplyResult(ApplyResult result) {
        StringBuilder sb = new StringBuilder("Применено ")
                .append(result.appliedCount()).append(" из ").append(result.totalCount()).append('.');
        for (ActionOutcome outcome : result.outcomes()) {
            if (!outcome.success()) {
                sb.append('\n').append(outcome.summary()).append(" — ").append(outcome.error());
            }
        }
        return sb.toString();
    }
}
