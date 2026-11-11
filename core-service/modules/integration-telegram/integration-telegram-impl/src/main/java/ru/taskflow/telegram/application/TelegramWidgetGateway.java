package ru.taskflow.telegram.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.shared.widgets.*;
import ru.taskflow.user.api.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

@Service
public class TelegramWidgetGateway {
    private final WidgetStore store;
    private final WidgetApi api;
    private final WidgetProposalProjection projection;
    private final UserService users;
    private final AssistantService assistant;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String appUrl;
    private final WidgetReceiptProjection receipts;
    public TelegramWidgetGateway(WidgetStore store, WidgetApi api, WidgetProposalProjection projection,
            UserService users, AssistantService assistant, ObjectMapper mapper,
            @Value("${app.telegram.widgets-enabled:false}") boolean enabled,
            @Value("${app.public-base-url:}") String appUrl,WidgetReceiptProjection receipts) {
        this.store=store;this.api=api;this.projection=projection;this.users=users;
        this.assistant=assistant;this.mapper=mapper;this.enabled=enabled;this.appUrl=appUrl;this.receipts=receipts;
    }
    public boolean sendApplied(UUID userId,long chatId,Proposal proposal,ru.taskflow.assistant.api.dto.ApplyResult result) {
        if(chatId<=0 || proposal.id()==null) return false;
        var state=receipts.render(userId,proposal.id(),0);
        var context=store.reserve(userId,"RECEIPT",proposal.id(),null,null,chatId,state);
        if(context.messageId()!=null) return true;
        boolean card=enabled && users.getSettings(userId).telegramWidgetsEnabled();
        long message;
        String transport="WIDGET";
        if(card) {
            try { message=api.send(context,appUrl); }
            catch(RestClientResponseException e) {
                if(!WidgetApi.unsupported(e)) throw e;
                card=false;message=0;
            }
        } else message=0;
        if(!card) {
            context=new WidgetContext(context.id(),context.userId(),context.kind(),context.referenceId(),null,null,
                    context.chatId(),null,"TEXT",context.expiresAt(),context.revision(),context.page(),true,context.state());
            message=api.sendText(context,appUrl);transport="TEXT";
        }
        store.bind(context.id(),message,transport);return true;
    }
    public boolean sendProposal(UUID userId,long chatId,Proposal proposal) {
        if (!enabled || chatId<=0 || !proposal.hasActions() || proposal.hasClarification() || proposal.hasRejections()
                || !users.getSettings(userId).telegramWidgetsEnabled()) return false;
        if (proposal.actions().stream().anyMatch(a -> a.type()==ru.taskflow.assistant.api.AssistantActionType.UPDATE
                && java.util.List.of("title","description","priority","group").stream().filter(f->a.payload().get(f)!=null).count()>2)) return false;
        var state=projection.render(userId,proposal,0);
        try {
            for (int page=0;page<proposal.actions().size();page++) {
                var candidate=projection.render(userId,proposal,page);
                if (candidate.title().isBlank()) return false;
                WidgetTemplate.encode(mapper,candidate.identity(UUID.randomUUID(),0));
            }
        }
        catch (IllegalArgumentException tooLarge) { return false; }
        var context=store.reserve(userId,"PROPOSAL",proposal.id(),null,null,chatId,state);
        if (context.messageId()!=null) return true;
        long message;
        String transport="WIDGET";
        try { message=api.send(context,appUrl); }
        catch (RestClientResponseException unsupported) {
            if (!WidgetApi.unsupported(unsupported)) throw unsupported;
            context=new WidgetContext(context.id(),context.userId(),context.kind(),context.referenceId(),null,null,
                    context.chatId(),null,"TEXT",context.expiresAt(),context.revision(),context.page(),true,context.state());
            message=api.sendText(context,appUrl);transport="TEXT";
        }
        store.bind(context.id(),message,transport);
        return true;
    }
    @Transactional
    public void refreshProposal(UUID userId,UUID proposalId) {
        if (!enabled) return;
        for (var previous:store.proposals(userId,proposalId)) {
            var context=store.lock(previous.id(),userId).orElseThrow();
            var proposal=assistant.findById(userId,proposalId);
            if (!proposal.hasActions()) continue;
            store.save(context,projection.render(userId,proposal,context.page()),context.page(),context.expanded());
        }
    }
}
