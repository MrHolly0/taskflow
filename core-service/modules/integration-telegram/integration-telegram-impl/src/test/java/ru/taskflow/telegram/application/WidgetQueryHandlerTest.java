package ru.taskflow.telegram.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.taskflow.shared.widgets.*;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.*;
import ru.taskflow.user.api.*;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WidgetQueryHandlerTest {
    final WidgetActions actions=mock(WidgetActions.class);
    final WidgetApi api=mock(WidgetApi.class);
    final UserService users=mock(UserService.class);
    final TelegramMessageSender sender=mock(TelegramMessageSender.class);
    final TelegramUpdateCheckpoint checkpoint=mock(TelegramUpdateCheckpoint.class);
    final WidgetQueryHandler handler=new WidgetQueryHandler(actions,api,users,sender,checkpoint);
    @Test void forwardedOrUnboundQueriesNeverReachBusinessLogic() {
        handler.handle(query(999L,"private"));handler.handle(query(123L,"group"));
        verifyNoInteractions(actions,users);verify(api,times(2)).answerError(eq("q"),anyString());
    }
    @Test void actorComesFromTelegramIdentityAndDeadlineComesFromInbox() {
        UUID owner=UUID.randomUUID();var deadline=Instant.now().plusSeconds(10);
        var state=new WidgetState("reminder","Задача",List.of());
        when(users.findIdentityOwner(IdentityProvider.TELEGRAM,"123")).thenReturn(Optional.of(owner));
        when(checkpoint.widgetDeadline()).thenReturn(deadline);
        when(actions.act(owner,123L,456L,"test",deadline)).thenReturn(state);
        handler.handle(query(123L,"private"));
        verify(actions).act(owner,123L,456L,"test",deadline);verify(api).answer("q",state);verify(users).recordActivity(owner);
    }
    @Test void failedActionDoesNotAcknowledgeSuccess() {
        UUID owner=UUID.randomUUID();when(users.findIdentityOwner(IdentityProvider.TELEGRAM,"123")).thenReturn(Optional.of(owner));
        when(actions.act(eq(owner),anyLong(),anyLong(),anyString(),any())).thenThrow(new IllegalArgumentException("Напоминания выключены"));
        handler.handle(query(123L,"private"));verify(api).answerError("q","Напоминания выключены");verify(api,never()).answer(anyString(),any());
    }
    private TelegramWidgetQuery query(long chat,String type) {
        return new TelegramWidgetQuery("q",new TelegramUser(123,"u","Имя",null),new TelegramWidgetQuery.Chat(chat,type),456L,
                new ObjectMapper().valueToTree(Map.of("type","data")),"test");
    }
}
