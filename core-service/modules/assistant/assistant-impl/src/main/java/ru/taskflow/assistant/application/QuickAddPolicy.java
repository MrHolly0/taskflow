package ru.taskflow.assistant.application;

import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.Proposal;

/**
 * Создание аддитивно и обратимо — худший исход автоприменения на кнопке
 * быстрого добавления это лишняя задача, которую видно и легко удалить.
 * Закрытие, отмена и перенос меняют то, что человек уже накопил, поэтому
 * при любом другом типе действия, при уточняющем вопросе или при пустом
 * списке предложение возвращается на подтверждение, а не применяется само.
 */
@Component
public class QuickAddPolicy {

    public boolean shouldAutoApply(Proposal proposal) {
        if (proposal.status() != ProposalStatus.PENDING) {
            return false;
        }
        if (proposal.hasClarification() || !proposal.hasActions()) {
            return false;
        }
        return proposal.actions().stream().allMatch(a -> a.type() == AssistantActionType.CREATE);
    }
}
