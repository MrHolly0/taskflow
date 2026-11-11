package ru.taskflow.assistant.application;

import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.Proposal;

@Component
public class AutomaticActionPolicy {
    public boolean eligible(Proposal proposal) {
        if (proposal.status()!=ProposalStatus.PENDING || !proposal.hasActions() || proposal.hasClarification()
                || proposal.hasRejections() || proposal.exclusive()
                || (proposal.ambiguityReason()!=null && !proposal.ambiguityReason().isBlank())
                || proposal.actions().stream().anyMatch(a->!a.accepted())) return false;
        if(proposal.actions().stream().allMatch(a->a.type()==AssistantActionType.CREATE)) return true;
        return proposal.actions().size()==1 && proposal.actions().getFirst().type()==AssistantActionType.COMPLETE;
    }
}
