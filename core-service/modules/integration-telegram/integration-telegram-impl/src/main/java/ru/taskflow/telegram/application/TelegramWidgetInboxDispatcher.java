package ru.taskflow.telegram.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.taskflow.shared.widgets.WidgetStore;
import java.time.Duration;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.telegram.widgets-enabled",havingValue="true")
public class TelegramWidgetInboxDispatcher {
    private final TelegramUpdateInbox inbox;
    private final UpdateRouter router;
    private final TelegramUpdateCheckpoint checkpoint;
    private final WidgetStore store;
    public TelegramWidgetInboxDispatcher(TelegramUpdateInbox inbox,UpdateRouter router,TelegramUpdateCheckpoint checkpoint,WidgetStore store) {
        this.inbox=inbox;this.router=router;this.checkpoint=checkpoint;this.store=store;
    }
    @Scheduled(fixedDelay=250,scheduler="telegramWidgetScheduler")
    public void dispatch() {
        inbox.claimWidget().ifPresent(update->{
            try { checkpoint.within(update.id(),()->router.route(update.update())); inbox.complete(update); }
            catch (Exception e) { inbox.fail(update,false,Duration.ZERO,e.getClass().getSimpleName()); }
        });
    }
    @Scheduled(cron="0 25 * * * *",scheduler="telegramWidgetScheduler")
    public void prune() { store.prune(); }
}
