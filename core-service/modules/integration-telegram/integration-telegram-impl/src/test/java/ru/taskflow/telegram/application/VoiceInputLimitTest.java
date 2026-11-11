package ru.taskflow.telegram.application;
import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.shared.resources.ResourcePolicy;
import ru.taskflow.telegram.infrastructure.client.*;
import ru.taskflow.telegram.infrastructure.client.dto.*;
import java.util.UUID;
import static org.mockito.Mockito.*;
class VoiceInputLimitTest {
    @Test void oversizedVoiceNeverDownloadsOrCallsModel() {
        var client=mock(TelegramApiClient.class);var sender=mock(TelegramMessageSender.class);var assistant=mock(AssistantService.class);
        var handler=new VoiceMessageHandler(client,sender,assistant,mock(ProposalMessageRenderer.class),new TelegramUpdateCheckpoint(null),
                mock(TelegramWidgetGateway.class),new TelegramResourceFeedback(sender),new ResourcePolicy());
        handler.handle(new TelegramMessage(1,new TelegramUser(123,"u","Имя",null),new TelegramChat(123),null,new TelegramVoice("file",121)),UUID.randomUUID());
        verifyNoInteractions(client,assistant);verify(sender).sendMessage(123,"Голосовое длиннее двух минут, раздели его на несколько сообщений");
    }
}
