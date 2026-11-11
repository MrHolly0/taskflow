package ru.taskflow.telegram.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class TelegramInboxConfig {

    @Bean
    public ThreadPoolTaskScheduler telegramWidgetScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setThreadNamePrefix("telegram-widgets-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(15);
        return scheduler;
    }

    @Bean
    public ThreadPoolTaskScheduler telegramInboxScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setThreadNamePrefix("telegram-inbox-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
