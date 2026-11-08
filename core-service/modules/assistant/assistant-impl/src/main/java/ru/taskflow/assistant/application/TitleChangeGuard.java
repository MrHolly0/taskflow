package ru.taskflow.assistant.application;

import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * update_task может сменить title только если в реплике упомянуто текущее
 * название задачи. Правило в промпте закрывает случай «нет такой задачи
 * в списке» — не случай «задача есть, но реплика двусмысленна», когда
 * промпт уверенно выполняет update не по той цели. Сверка — точное
 * вхождение нормализованной строки, детерминированная, без порога сходства
 * (в отличие от DuplicateGuard: здесь не нужна снисходительность к опечаткам,
 * нужна гарантия, что название реально названо).
 */
@Component
public class TitleChangeGuard {

    private static final String REJECTION_PREFIX = "смена названия отклонена: ";
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public record GuardResult(List<ProposedAction> actions, List<String> rejections) {}

    public GuardResult filter(List<ProposedAction> actions, TaskContextWindow window, String userText) {
        String normalizedText = normalize(userText);
        List<ProposedAction> kept = new ArrayList<>();
        List<String> rejections = new ArrayList<>();

        for (ProposedAction action : actions) {
            if (!isTitleChange(action)) {
                kept.add(action);
                continue;
            }

            String currentTitle = window.titleFor(action.targetTaskId());
            if (currentTitle == null || mentioned(normalizedText, currentTitle)) {
                kept.add(action);
                continue;
            }

            rejections.add(REJECTION_PREFIX + "текущее название задачи не упомянуто в реплике ("
                    + currentTitle + ")");
        }

        return new GuardResult(renumber(kept), rejections);
    }

    public static boolean isPolicyRejection(String reason) {
        return reason != null && reason.startsWith(REJECTION_PREFIX);
    }

    private boolean isTitleChange(ProposedAction action) {
        if (action.type() != AssistantActionType.UPDATE || action.targetTaskId() == null) {
            return false;
        }
        Object title = action.payload().get("title");
        return title != null && !title.toString().isBlank();
    }

    private boolean mentioned(String normalizedText, String title) {
        String normalizedTitle = normalize(title);
        return !normalizedTitle.isBlank() && normalizedText.contains(normalizedTitle);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        String stripped = NON_WORD.matcher(lower).replaceAll(" ");
        return WHITESPACE.matcher(stripped).replaceAll(" ").trim();
    }

    private List<ProposedAction> renumber(List<ProposedAction> actions) {
        List<ProposedAction> renumbered = new ArrayList<>(actions.size());
        int ordinal = 1;
        for (ProposedAction action : actions) {
            renumbered.add(new ProposedAction(ordinal++, action.type(), action.targetTaskId(),
                    action.payload(), action.summary(), action.accepted()));
        }
        return renumbered;
    }
}
