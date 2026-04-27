package ru.taskflow.telegram.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import ru.taskflow.telegram.infrastructure.client.TelegramApiClient;

@Slf4j
@Configuration
@EnableRetry
public class TelegramBotConfig {

    @Value("${app.telegram.bot-token:}")
    private String botToken;

    @Value("${app.telegram.miniapp-url:}")
    private String miniappUrl;

    @Bean
    public TelegramApiClient telegramApiClient() {
        return new TelegramApiClient(botToken);
    }

    @PostConstruct
    public void setupMenuButton() {
        if (botToken.isBlank() || miniappUrl.isBlank()) return;
        try {
            telegramApiClient().setDefaultMenuButton(miniappUrl, "TaskFlow");
            log.info("Telegram menu button set to {}", miniappUrl);
        } catch (Exception e) {
            log.warn("Failed to set Telegram menu button: {}", e.getMessage());
        }
    }
}