package ru.taskflow.telegram.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import ru.taskflow.telegram.application.VoiceMessageHandler;
import ru.taskflow.telegram.infrastructure.client.TelegramApiClient;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;

@Configuration
@EnableRetry
public class TelegramBotConfig {

    @Value("${app.telegram.bot-token:}")
    private String botToken;

    @Value("${app.telegram.voice-dir:/tmp/taskflow/voice}")
    private String voiceDir;

    @Bean
    public TelegramApiClient telegramApiClient() {
        return new TelegramApiClient(botToken);
    }

    @Bean
    public VoiceMessageHandler voiceMessageHandler(TelegramApiClient apiClient, TelegramMessageSender sender) {
        return new VoiceMessageHandler(apiClient, sender, voiceDir);
    }
}