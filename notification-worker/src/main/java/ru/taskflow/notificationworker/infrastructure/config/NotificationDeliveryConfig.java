package ru.taskflow.notificationworker.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class NotificationDeliveryConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService notificationExecutor(@Value("${app.notification.concurrency:4}") int concurrency) {
        return Executors.newFixedThreadPool(Math.clamp(concurrency, 1, 16),
                Thread.ofPlatform().name("notification-delivery-", 0).factory());
    }
}
