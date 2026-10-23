package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.assistant.api.exception.ProposalNotFoundException;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramCallbackQuery;

import java.util.UUID;

/**
 * Обработчик inline-кнопок и обратных вызовов от Telegram.
 *
 * Кнопки предложения кодируются коротким кодом, а не UUID — callback_data
 * Telegram ограничен 64 байтами, ровно для этого в части 2а сделан
 * ShortCodeGenerator. Формат: p:{shortCode}:a — применить, p:{shortCode}:r —
 * отклонить, p:{shortCode}:t:{ordinal} — переключить действие.
 */
@Service
@RequiredArgsConstructor
public class CallbackHandler {

    private final TaskService taskService;
    private final AssistantService assistantService;
    private final ProposalMessageRenderer renderer;
    private final TelegramMessageSender sender;
    private final TelegramUpdateCheckpoint checkpoint;

    public void handle(TelegramCallbackQuery callback, UUID userId) {
        String data = callback.data();
        if (data == null || !data.contains(":")) {
            sender.answerCallback(callback.id(), "Неизвестная команда");
            return;
        }

        int sep = data.indexOf(':');
        String action = data.substring(0, sep);
        String payload = data.substring(sep + 1);

        switch (action) {
            case "complete" -> {
                taskService.complete(userId, UUID.fromString(payload));
                sender.answerCallback(callback.id(), "✅ Выполнено!");
            }
            case "delete" -> {
                taskService.delete(userId, UUID.fromString(payload));
                sender.answerCallback(callback.id(), "🗑 Удалено");
            }
            case "p" -> handleProposal(callback, userId, payload);
            case "edit" -> {
                sender.answerCallback(callback.id(), "✏️ Редактирование");
                sender.sendMessage(callback.message().chat().id(),
                    "Для редактирования задачи используйте кнопку в её карточке или команду /update");
            }
            default -> sender.answerCallback(callback.id(), "Неизвестная команда");
        }
    }

    private void handleProposal(TelegramCallbackQuery callback, UUID userId, String payload) {
        String[] parts = payload.split(":");
        if (parts.length < 2) {
            sender.answerCallback(callback.id(), "Неизвестная команда");
            return;
        }

        Proposal proposal;
        try {
            proposal = assistantService.findByShortCode(userId, parts[0]);
        } catch (ProposalNotFoundException e) {
            // Чужой короткий код и несуществующий неотличимы намеренно — не
            // подтверждаем чужому пользователю, что предложение вообще есть.
            sender.answerCallback(callback.id(), "Предложение не найдено");
            return;
        }

        switch (parts[1]) {
            case "a" -> applyProposal(callback, userId, proposal);
            case "r" -> rejectProposal(callback, userId, proposal);
            case "t" -> toggleAction(callback, userId, proposal, parts);
            default -> sender.answerCallback(callback.id(), "Неизвестная команда");
        }
    }

    private void applyProposal(TelegramCallbackQuery callback, UUID userId, Proposal proposal) {
        ApplyResult result = checkpoint.once("apply", ApplyResult.class,
                () -> assistantService.apply(userId, proposal.id()));
        sender.answerCallback(callback.id(), "Готово");
        sender.editMessage(callback.message().chat().id(), callback.message().messageId(),
                renderer.renderApplyResult(result));
    }

    private void rejectProposal(TelegramCallbackQuery callback, UUID userId, Proposal proposal) {
        assistantService.reject(userId, proposal.id());
        sender.answerCallback(callback.id(), "Отклонено");
        sender.editMessage(callback.message().chat().id(), callback.message().messageId(), "Предложение отклонено.");
    }

    private void toggleAction(TelegramCallbackQuery callback, UUID userId, Proposal proposal, String[] parts) {
        int ordinal;
        try {
            ordinal = Integer.parseInt(parts[2]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            sender.answerCallback(callback.id(), "Неизвестная команда");
            return;
        }

        boolean currentlyAccepted = proposal.actions().stream()
                .filter(a -> a.ordinal() == ordinal)
                .findFirst()
                .map(ProposedAction::accepted)
                .orElse(true);

        boolean desired = checkpoint.once("accepted", Boolean.class, () -> !currentlyAccepted);
        Proposal updated = checkpoint.once("updated", Proposal.class,
                () -> assistantService.setActionAccepted(userId, proposal.id(), ordinal, desired));
        sender.answerCallback(callback.id(), "Обновлено");
        sender.editMessage(callback.message().chat().id(), callback.message().messageId(),
                renderer.render(updated), renderer.keyboard(updated));
    }
}
