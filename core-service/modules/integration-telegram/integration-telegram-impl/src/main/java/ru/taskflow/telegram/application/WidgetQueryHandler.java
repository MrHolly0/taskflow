package ru.taskflow.telegram.application;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import ru.taskflow.shared.widgets.WidgetApi;
import ru.taskflow.shared.widgets.WidgetState;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramCallbackQuery;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramWidgetQuery;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserService;
import ru.taskflow.shared.exception.ValidationException;
import java.util.UUID;

@Service
public class WidgetQueryHandler {
    private final WidgetActions actions;
    private final WidgetApi api;
    private final UserService users;
    private final TelegramMessageSender sender;
    private final TelegramUpdateCheckpoint checkpoint;
    public WidgetQueryHandler(WidgetActions actions,WidgetApi api,UserService users,TelegramMessageSender sender,TelegramUpdateCheckpoint checkpoint) {
        this.actions=actions;this.api=api;this.users=users;this.sender=sender;this.checkpoint=checkpoint;
    }
    public void handle(TelegramWidgetQuery query) {
        if (query.id()==null) return;
        WidgetState state=null;
        String error="Карточка недоступна — открой Мунин";
        if (query.from()!=null && query.chat()!=null && "private".equals(query.chat().type())
                && query.chat().id()==query.from().id() && query.messageId()!=null && query.type()!=null
                && "data".equals(query.type().path("type").asText())) {
            var owner=users.findIdentityOwner(IdentityProvider.TELEGRAM,Long.toString(query.from().id()));
            if (owner.isPresent()) {
                try { state=actions.act(owner.get(),query.chat().id(),query.messageId(),query.data(),checkpoint.widgetDeadline()); users.recordActivity(owner.get()); }
                catch (IllegalArgumentException | ValidationException e) { error=e.getMessage(); }
                catch (RuntimeException e) { error="Не получилось сохранить — попробуй ещё раз"; }
            }
        }
        try { if (state==null) api.answerError(query.id(),error); else api.answer(query.id(),state); }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value()!=400) throw e;
            // Состояние всё равно уйдёт в сообщение через очередь, даже если срок ответа истёк.
        }
    }
    public void handleCallback(TelegramCallbackQuery callback,UUID userId) {
        String text="Карточка недоступна";
        if (callback.from()!=null && callback.message()!=null && callback.message().chat().id()==callback.from().id()) {
            try { actions.act(userId,callback.message().chat().id(),callback.message().messageId(),callback.data()); text="Готово"; }
            catch (IllegalArgumentException | ValidationException e) { text=e.getMessage(); }
        }
        sender.answerCallback(callback.id(),text);
    }
}
