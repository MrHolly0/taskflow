package ru.taskflow.app.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.taskflow.task.application.TaskReminderService;

import java.time.OffsetDateTime;

/**
 * Продвигает цепочки настойчивых напоминаний (docs/vkr/исследование-напоминания.md,
 * §3.3) — раз в пять минут ищет повторы, время которых настало без решения
 * человека, и создаёт следующий шаг цепочки, если запас (Б3, затухание) не
 * исчерпан. Сама логика — в TaskReminderService.advancePersistentChains,
 * здесь только тактовый вызов, как и у TaskCleanupScheduler.
 */
@Component
@RequiredArgsConstructor
public class PersistentReminderChainScheduler {

    private final TaskReminderService taskReminderService;
    private final ClusterJobRunner jobs;

    @Scheduled(cron = "0 */5 * * * *")
    public void advanceChains() {
        jobs.run(910003L, () -> taskReminderService.advancePersistentChains(OffsetDateTime.now()));
    }
}
