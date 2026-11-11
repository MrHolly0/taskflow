package ru.taskflow.notificationworker.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.taskflow.shared.widgets.*;
import ru.taskflow.notificationworker.infrastructure.db.PendingNotification;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class WidgetDelivery {
    private final JdbcTemplate jdbc;
    private final WidgetStore store;
    private final WidgetApi api;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String appUrl;
    public WidgetDelivery(JdbcTemplate jdbc,WidgetStore store,WidgetApi api,ObjectMapper mapper,
            @Value("${app.telegram.widgets-enabled:false}") boolean enabled,
            @Value("${app.public-base-url:}") String appUrl) {
        this.jdbc=jdbc;this.store=store;this.api=api;this.mapper=mapper;this.enabled=enabled;this.appUrl=appUrl;
    }
    public boolean send(PendingNotification notification) {
        if (!enabled || !"TELEGRAM".equals(notification.channel()) || Long.parseLong(notification.destination())<=0) return false;
        var rows=jdbc.query("""
                SELECT n.user_id,n.task_id,n.engagement_prompt_id,t.title,t.deadline,u.timezone,
                       p.deadline_snapshot,s.telegram_widgets_enabled
                FROM scheduled_notifications n JOIN users u ON u.id=n.user_id
                JOIN user_settings s ON s.user_id=n.user_id
                LEFT JOIN tasks t ON t.id=n.task_id AND t.user_id=n.user_id AND NOT t.is_deleted
                LEFT JOIN engagement_prompts p ON p.id=n.engagement_prompt_id AND p.user_id=n.user_id
                WHERE n.id=? AND n.lease_token=? AND n.lease_until>NOW() AND NOT n.sent AND n.failed_at IS NULL
                  AND n.destination=? AND n.channel=? AND n.payload_type=?
                  AND EXISTS (SELECT 1 FROM user_identities i WHERE i.user_id=n.user_id
                    AND i.provider='TELEGRAM' AND i.external_id=n.destination)
                """,(rs,row)->new Target(rs.getObject("user_id",UUID.class),rs.getObject("task_id",UUID.class),
                rs.getObject("engagement_prompt_id",UUID.class),rs.getString("title"),rs.getObject("deadline",OffsetDateTime.class),
                rs.getString("timezone"),rs.getObject("deadline_snapshot",OffsetDateTime.class),rs.getBoolean("telegram_widgets_enabled")),
                notification.id(),notification.leaseToken(),notification.destination(),notification.channel(),notification.payloadType());
        if (rows.isEmpty()) return true;
        var target=rows.getFirst();
        if (!target.cards()) return false;
        boolean reminder="TASK_REMINDER".equals(notification.payloadType());
        if ((reminder || "TASK_CHECKIN".equals(notification.payloadType())) && target.title()==null) return true;
        String kind=reminder?"reminder":"TASK_CHECKIN".equals(notification.payloadType())?"checkin":"return";
        String title=kind.equals("return")?"Расскажи о новых делах":WidgetTemplate.clip(target.title(),80);
        var state=new WidgetState(kind,title,kind.equals("return")?List.of():
                List.of(List.of("Срок",WidgetTemplate.time(target.deadline(),ZoneId.of(target.timezone()),Clock.systemUTC()))));
        var context=store.reserve(target.userId(),reminder?"REMINDER":"ENGAGEMENT",
                reminder?notification.id():target.promptId(),target.taskId(),
                reminder?target.deadline():target.snapshot(),Long.parseLong(notification.destination()),state);
        if (context.messageId()!=null) return true;
        long messageId;
        String transport="WIDGET";
        try { messageId=api.send(context,appUrl); }
        catch (RestClientResponseException e) {
            if (!WidgetApi.unsupported(e)) throw deliveryError(e);
            context=new WidgetContext(context.id(),context.userId(),context.kind(),context.referenceId(),context.taskId(),context.deadline(),
                    context.chatId(),null,"TEXT",context.expiresAt(),context.revision(),context.page(),true,context.state());
            messageId=api.sendText(context,appUrl);transport="TEXT";
        }
        store.bind(context.id(),messageId,transport);
        return true;
    }
    public void sync(PendingNotification notification) {
        Sync payload;
        try { payload=mapper.readValue(notification.payload(),Sync.class); }
        catch (Exception e) { throw new NotificationDeliveryException("INVALID_WIDGET_SYNC",true,null); }
        var owners=jdbc.query("""
                SELECT n.user_id FROM scheduled_notifications n
                JOIN telegram_widget_contexts w ON w.id=n.widget_context_id AND w.user_id=n.user_id
                WHERE n.id=? AND n.lease_token=? AND n.lease_until>NOW() AND NOT n.sent AND n.failed_at IS NULL
                  AND w.id=? AND w.chat_id::text=n.destination
                  AND EXISTS (SELECT 1 FROM user_identities i WHERE i.user_id=w.user_id AND i.provider='TELEGRAM'
                    AND i.external_id=n.destination)
                  AND (w.task_id IS NULL OR EXISTS (SELECT 1 FROM tasks t WHERE t.id=w.task_id AND t.user_id=w.user_id))
                  AND (w.kind<>'PROPOSAL' OR EXISTS (SELECT 1 FROM assistant_proposals p WHERE p.id=w.reference_id AND p.user_id=w.user_id))
                """,(rs,row)->rs.getObject(1,UUID.class),notification.id(),notification.leaseToken(),payload.contextId());
        if (owners.isEmpty()) return;
        var context=store.find(payload.contextId(),owners.getFirst()).orElseThrow();
        if (context.messageId()==null) return;
        // Отправляем именно захваченную версию; новая останется в очереди после подтверждения этой.
        var snapshot=new WidgetContext(context.id(),context.userId(),context.kind(),context.referenceId(),context.taskId(),context.deadline(),
                context.chatId(),context.messageId(),context.transport(),context.expiresAt(),payload.revision(),context.page(),payload.expanded(),payload.state());
        try { api.sync(snapshot,appUrl); }
        catch (RestClientResponseException e) { throw deliveryError(e); }
    }
    private NotificationDeliveryException deliveryError(RestClientResponseException e) {
        java.time.Duration delay=NotificationRetryPolicy.parseRetryAfter(e.getResponseHeaders()==null?null:e.getResponseHeaders().getFirst("Retry-After"));
        if (e.getStatusCode().value()==429) {
            try { long seconds=mapper.readTree(e.getResponseBodyAsByteArray()).path("parameters").path("retry_after").asLong();
                if(seconds>0) delay=java.time.Duration.ofSeconds(Math.min(seconds,604800));
            } catch(Exception ignored) { }
        }
        int status=e.getStatusCode().value();
        return new NotificationDeliveryException("TELEGRAM_HTTP_"+status,status>=400&&status<500&&status!=408&&status!=429,delay);
    }
    private record Target(UUID userId,UUID taskId,UUID promptId,String title,OffsetDateTime deadline,String timezone,OffsetDateTime snapshot,boolean cards) {}
    public record Sync(UUID contextId,long revision,WidgetState state,boolean expanded) {}
}
