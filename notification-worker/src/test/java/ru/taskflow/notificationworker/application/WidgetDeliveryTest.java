package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.notificationworker.infrastructure.db.PendingNotification;
import ru.taskflow.shared.widgets.*;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers
class WidgetDeliveryTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    WidgetStore store;WidgetApi api;WidgetDelivery delivery;
    UUID owner,task,notification,lease;
    @BeforeAll static void schema() throws Exception {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE users(id UUID PRIMARY KEY,timezone TEXT)");
        jdbc.execute("CREATE TABLE user_settings(user_id UUID PRIMARY KEY)");
        jdbc.execute("CREATE TABLE user_identities(user_id UUID,provider TEXT,external_id TEXT)");
        jdbc.execute("CREATE TABLE tasks(id UUID PRIMARY KEY,user_id UUID,title TEXT,deadline TIMESTAMPTZ,is_deleted BOOLEAN DEFAULT FALSE,status TEXT DEFAULT 'TODO',started_at TIMESTAMPTZ,planned_date TIMESTAMPTZ,planned_date_set_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE assistant_proposals(id UUID PRIMARY KEY,user_id UUID)");
        jdbc.execute("CREATE TABLE engagement_prompts(id UUID PRIMARY KEY,user_id UUID,task_id UUID,kind TEXT,deadline_snapshot TIMESTAMPTZ,started_snapshot TIMESTAMPTZ,answer TEXT,cancelled_at TIMESTAMPTZ,expires_at TIMESTAMPTZ,batch_id UUID,batch_position INT)");
        jdbc.execute("CREATE TABLE telegram_update_inbox(update_id BIGINT,next_attempt_at TIMESTAMPTZ,status TEXT)");
        jdbc.execute("""
                CREATE TABLE scheduled_notifications(id UUID PRIMARY KEY,user_id UUID,task_id UUID,engagement_prompt_id UUID,
                  channel TEXT,destination TEXT,fire_at TIMESTAMPTZ,payload_type TEXT,payload JSONB,sent BOOLEAN DEFAULT FALSE,
                  sent_at TIMESTAMPTZ,retry_count INT DEFAULT 0,next_attempt_at TIMESTAMPTZ DEFAULT NOW(),
                  failed_at TIMESTAMPTZ,lease_token UUID,lease_until TIMESTAMPTZ,last_error TEXT)
                """);
        Path root=Path.of("").toAbsolutePath();while(!Files.exists(root.resolve("gradlew")))root=root.getParent();
        String yaml=Files.readString(root.resolve("core-service/app/src/main/resources/db/changelog/047-telegram-widgets.yaml"));
        for(String sql:yaml.substring(yaml.indexOf("sql: |")+6).stripIndent().split(";"))if(!sql.isBlank())jdbc.execute(sql.trim());
        jdbc.execute("ALTER TABLE telegram_widget_contexts DROP CONSTRAINT telegram_widget_contexts_kind_check");
        jdbc.execute("ALTER TABLE telegram_widget_contexts ADD CONSTRAINT telegram_widget_contexts_kind_check CHECK (kind IN ('REMINDER','ENGAGEMENT','PROPOSAL','RECEIPT','CHECKINS'))");
    }
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users,tasks,user_settings,user_identities,engagement_prompts,telegram_widget_contexts,scheduled_notifications CASCADE");
        store=new WidgetStore(jdbc,mapper,Clock.systemUTC());api=mock(WidgetApi.class);delivery=new WidgetDelivery(jdbc,store,api,mapper,true,"https://munin.test",new CheckinBatchView(jdbc,Clock.systemUTC()));
        owner=UUID.randomUUID();task=UUID.randomUUID();notification=UUID.randomUUID();lease=UUID.randomUUID();
        jdbc.update("INSERT INTO users VALUES (?,'Europe/Moscow')",owner);
        jdbc.update("INSERT INTO user_settings(user_id) VALUES (?)",owner);
        jdbc.update("INSERT INTO user_identities VALUES (?,'TELEGRAM','12345')",owner);
        jdbc.update("INSERT INTO tasks(id,user_id,title,deadline) VALUES (?,?,?,?)",task,owner,"Провести пару",OffsetDateTime.now().plusHours(1));
        jdbc.update("""
                INSERT INTO scheduled_notifications(id,user_id,task_id,channel,destination,payload_type,payload,fire_at,lease_token,lease_until)
                VALUES (?,?,?,'TELEGRAM','12345','TASK_REMINDER','{}',NOW(),?,NOW()+INTERVAL '2 minutes')
                """,notification,owner,task,lease);
        when(api.send(any(),anyString())).thenReturn(100L);
    }
    @Test void batchUsesOneWidgetForSeveralQuestions() {
        batch();assertThat(delivery.send(batchClaim())).isTrue();assertThat(delivery.send(batchClaim())).isTrue();
        verify(api,times(1)).send(any(),anyString());
        var c=context();assertThat(c.kind()).isEqualTo("CHECKINS");assertThat(c.state().total()).isEqualTo(2);
        assertThat(WidgetKeyboard.of(c,"https://munin.test").toString()).contains("Подробнее");
    }
    @Test void batchKeepsOneTextMessageWithPagingWhenWidgetsAreDisabled() {
        batch();delivery=new WidgetDelivery(jdbc,store,api,mapper,false,"https://munin.test",new CheckinBatchView(jdbc,Clock.systemUTC()));
        when(api.sendText(any(),anyString())).thenReturn(555L);
        assertThat(delivery.send(batchClaim())).isTrue();assertThat(delivery.send(batchClaim())).isTrue();
        verify(api,never()).send(any(),anyString());verify(api,times(1)).sendText(any(),anyString());
        var c=context();assertThat(c.transport()).isEqualTo("TEXT");assertThat(c.expanded()).isTrue();
        assertThat(WidgetKeyboard.of(c,"https://munin.test").toString()).contains(":pg1",":done");
    }
    private void batch() {
        UUID root=UUID.randomUUID();
        jdbc.update("INSERT INTO engagement_prompts(id,user_id,kind,expires_at) VALUES (?,?,'CHECKIN_BATCH',NOW()+INTERVAL '7 days')",root,owner);
        UUID other=UUID.randomUUID();
        jdbc.update("INSERT INTO tasks(id,user_id,title,deadline) VALUES (?,?,?,NOW()+INTERVAL '1 hour')",other,owner,"Разобрать отчёт");
        for(int i=0;i<2;i++) jdbc.update("INSERT INTO engagement_prompts(id,user_id,task_id,kind,deadline_snapshot,expires_at,batch_id,batch_position) SELECT ?,user_id,id,'TASK_CHECKIN',deadline,NOW()+INTERVAL '7 days',?,? FROM tasks WHERE id=?",
                UUID.randomUUID(),root,i,i==0?task:other);
        jdbc.update("UPDATE scheduled_notifications SET task_id=NULL,payload_type='CHECKIN_BATCH',engagement_prompt_id=? WHERE id=?",root,notification);
    }
    @Test void reminderUsesCurrentOwnedTaskAndBindsOneMessage() {
        assertThat(delivery.send(claim())).isTrue();assertThat(delivery.send(claim())).isTrue();
        verify(api,times(1)).send(any(),anyString());
        var c=context();assertThat(c.messageId()).isEqualTo(100L);assertThat(c.state().title()).isEqualTo("Провести пару");
        assertThat(c.state().key()).startsWith("w:"+c.id()+":0");
    }
    @Test void explicitUnsupportedMethodFallsBackToOrdinaryButtons() {
        when(api.send(any(),anyString())).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND,"Not found",HttpHeaders.EMPTY,new byte[0],null));
        when(api.sendText(any(),anyString())).thenReturn(101L);
        assertThat(delivery.send(claim())).isTrue();var c=context();
        assertThat(c.transport()).isEqualTo("TEXT");assertThat(c.expanded()).isTrue();assertThat(c.messageId()).isEqualTo(101);
        assertThat(WidgetKeyboard.of(c,"https://munin.test").toString()).contains(":done");
    }
    @Test void plainPreferenceFallsBackButTransferredClaimNeverSendsNewOwnersDataToOldChat() {
        jdbc.update("UPDATE user_settings SET telegram_widgets_enabled=false");
        assertThat(delivery.send(claim())).isFalse();verifyNoInteractions(api);
        jdbc.update("UPDATE user_settings SET telegram_widgets_enabled=true");
        jdbc.update("UPDATE scheduled_notifications SET destination='99999' WHERE id=?",notification);
        assertThat(delivery.send(claim())).isTrue();verifyNoInteractions(api);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telegram_widget_contexts",Integer.class)).isZero();
    }
    @Test void coalescingKeepsLeaseAndDeliveryUsesClaimedSnapshot() {
        delivery.send(claim());var c=context();
        tx.executeWithoutResult(s->store.save(c,c.state().finish("Готово","Задача в работе"),0,false));
        UUID syncId=jdbc.queryForObject("SELECT id FROM scheduled_notifications WHERE payload_type='WIDGET_SYNC'",UUID.class);
        jdbc.update("UPDATE scheduled_notifications SET lease_token=?,lease_until=NOW()+INTERVAL '2 minutes' WHERE id=?",lease,syncId);
        String payload=jdbc.queryForObject("SELECT payload::text FROM scheduled_notifications WHERE id=?",String.class,syncId);
        var snapshot=new PendingNotification(syncId,"TELEGRAM","12345","WIDGET_SYNC",payload,lease,1);
        var current=store.find(c.id(),owner).orElseThrow();
        tx.executeWithoutResult(s->store.save(current,current.state().finish("Готово","Новое состояние"),0,false));
        assertThat(jdbc.queryForObject("SELECT lease_token FROM scheduled_notifications WHERE id=?",UUID.class,syncId)).isEqualTo(lease);
        delivery.sync(snapshot);var capture=ArgumentCaptor.forClass(WidgetContext.class);verify(api).sync(capture.capture(),anyString());
        assertThat(capture.getValue().revision()).isEqualTo(1);assertThat(capture.getValue().state().status()).isEqualTo("Задача в работе");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_notifications WHERE payload_type='WIDGET_SYNC'",Integer.class)).isEqualTo(1);
    }
    private PendingNotification batchClaim() { return new PendingNotification(notification,"TELEGRAM","12345","CHECKIN_BATCH","{}",lease,1); }
    private PendingNotification claim() { return new PendingNotification(notification,"TELEGRAM","12345","TASK_REMINDER","{}",lease,1); }
    private WidgetContext context() {
        UUID id=jdbc.queryForObject("SELECT id FROM telegram_widget_contexts",UUID.class);return store.find(id,owner).orElseThrow();
    }
}
