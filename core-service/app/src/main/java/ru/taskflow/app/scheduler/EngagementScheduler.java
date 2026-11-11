package ru.taskflow.app.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.taskflow.notify.api.EngagementService;

@Component
@ConditionalOnProperty(prefix = "app.notification", name = "engagement-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class EngagementScheduler {
    private final EngagementService engagement;
    private final ClusterJobRunner jobs;

    @Scheduled(fixedDelayString = "${app.notification.engagement-interval-ms:60000}",
            initialDelayString = "${app.notification.engagement-interval-ms:60000}")
    public void plan() {
        jobs.run(910005L, engagement::plan);
    }
}
