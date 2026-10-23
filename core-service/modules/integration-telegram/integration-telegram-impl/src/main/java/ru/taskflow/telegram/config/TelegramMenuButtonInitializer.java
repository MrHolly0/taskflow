package ru.taskflow.telegram.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.taskflow.telegram.infrastructure.client.TelegramApiClient;

/**
 * Ставит кнопку меню бота — не в жизненном цикле бинов (там она была раньше
 * и падала на цикле: {@code @PostConstruct} звал {@code telegramApiClient()}
 * из того же класса, который в этот момент ещё не достроен), а на
 * {@link ApplicationReadyEvent}. К этому моменту контекст уже полностью
 * поднят, поэтому обращения к бину нет — цикла нет по построению. И сеть:
 * запрос к Telegram теперь идёт после старта, а не во время него — старт
 * контейнера не должен зависеть от того, ответит ли внешний сервис вовремя.
 * <p>
 * Неудача — {@code error}, не {@code warn}: применить настроенную кнопку
 * меню не удалось, это не второстепенное предупреждение, которое можно
 * пропустить в логах. При этом старт приложения падать не должен — Telegram
 * может быть недоступен, это не повод не подняться.
 */
@Component
@Slf4j
public class TelegramMenuButtonInitializer {

    private final TelegramApiClient telegramApiClient;
    private final String botToken;
    private final String miniappUrl;
    private final String brandName;

    public TelegramMenuButtonInitializer(
            TelegramApiClient telegramApiClient,
            @Value("${app.telegram.bot-token:}") String botToken,
            @Value("${app.telegram.miniapp-url:}") String miniappUrl,
            @Value("${app.branding.name:Мунин}") String brandName) {
        this.telegramApiClient = telegramApiClient;
        this.botToken = botToken;
        this.miniappUrl = miniappUrl;
        this.brandName = brandName;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void setupMenuButton() {
        if (botToken.isBlank() || miniappUrl.isBlank()) {
            return;
        }
        try {
            telegramApiClient.setDefaultMenuButton(miniappUrl, brandName);
            log.info("Telegram menu button set to {}", miniappUrl);
        } catch (Exception e) {
            log.error("Failed to set Telegram menu button: {}", e.getClass().getSimpleName());
        }
    }
}
