package ru.taskflow.notificationworker.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.taskflow.shared.widgets.WidgetApi;
import ru.taskflow.shared.widgets.WidgetStore;
import java.time.Clock;

@Configuration
public class TelegramWidgetConfig {
    @Bean
    public ru.taskflow.shared.widgets.CheckinBatchView checkinBatchView(JdbcTemplate jdbc) {
        return new ru.taskflow.shared.widgets.CheckinBatchView(jdbc,Clock.systemUTC());
    }
    @Bean
    public WidgetStore telegramWidgetStore(JdbcTemplate jdbc,ObjectMapper mapper) {
        return new WidgetStore(jdbc,mapper,Clock.systemUTC());
    }
    @Bean
    public WidgetApi telegramWidgetApi(@Value("${app.telegram.api-base-url:https://api.telegram.org}") String base,
            @Value("${app.telegram.bot-token:}") String token,ObjectMapper mapper) {
        return new WidgetApi(base,token,mapper);
    }
}
