package ru.taskflow.telegram.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.taskflow.shared.widgets.WidgetStore;
import ru.taskflow.shared.widgets.WidgetApi;
import org.springframework.beans.factory.annotation.Value;
import java.time.Clock;

@Configuration
public class TelegramWidgetConfig {
    @Bean
    public WidgetApi telegramWidgetApi(@Value("${app.telegram.api-base-url:https://api.telegram.org}") String base,
            @Value("${app.telegram.bot-token:}") String token, ObjectMapper mapper) {
        return new WidgetApi(base,token,mapper);
    }

    @Bean
    public WidgetStore telegramWidgetStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        return new WidgetStore(jdbc,mapper,clock);
    }
}
