package ru.taskflow.telegram.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import ru.taskflow.telegram.infrastructure.client.TelegramApiClient;

@Configuration
@EnableRetry
public class TelegramBotConfig {

    @Value("${app.telegram.bot-token:}")
    private String botToken;

    @Bean
    public TelegramApiClient telegramApiClient() {
        return new TelegramApiClient(botToken);
    }
}