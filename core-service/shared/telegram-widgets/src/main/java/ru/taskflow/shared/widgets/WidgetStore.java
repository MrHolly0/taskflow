package ru.taskflow.shared.widgets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class WidgetStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public WidgetStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc; this.mapper = mapper; this.clock = clock;
    }
    @Transactional
    public WidgetContext reserve(UUID userId, String kind, UUID referenceId, UUID taskId,
                                 OffsetDateTime deadline, long chatId, WidgetState state) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO telegram_widget_contexts(id,user_id,kind,reference_id,task_id,deadline_snapshot,chat_id,state,expires_at,page)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?,?) ON CONFLICT (user_id,kind,reference_id,chat_id) DO NOTHING
                """, id,userId,kind,referenceId,taskId,deadline,chatId,WidgetTemplate.encode(mapper,state.identity(id,0)),OffsetDateTime.now(clock).plusDays(7),state.page()==null?0:state.page());
        return jdbc.query("SELECT * FROM telegram_widget_contexts WHERE user_id=? AND kind=? AND reference_id=? AND chat_id=?",
                this::map,userId,kind,referenceId,chatId).getFirst();
    }
    public Optional<WidgetContext> find(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM telegram_widget_contexts WHERE id=? AND user_id=?",this::map,id,userId).stream().findFirst();
    }
    public Optional<WidgetContext> lock(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM telegram_widget_contexts WHERE id=? AND user_id=? FOR UPDATE",this::map,id,userId).stream().findFirst();
    }
    @Transactional
    public void bind(UUID id, long messageId, String transport) {
        jdbc.update("UPDATE telegram_widget_contexts SET message_id=?, transport=?, expanded=(? = 'TEXT') WHERE id=? AND message_id IS NULL",messageId,transport,transport,id);
    }
    public WidgetContext save(WidgetContext context, WidgetState state, int page, boolean expanded) {
        var next = state.identity(context.id(),context.revision()+1);
        int changed = jdbc.update("""
                UPDATE telegram_widget_contexts SET revision=revision+1,state=?::jsonb,page=?,expanded=?
                WHERE id=? AND revision=?
                """,WidgetTemplate.encode(mapper,next),page,expanded,context.id(),context.revision());
        if (changed != 1) throw new IllegalStateException("WIDGET_REVISION_CONFLICT");
        var saved = find(context.id(),context.userId()).orElseThrow();
        enqueueSync(saved);
        return saved;
    }
    public void enqueueSync(WidgetContext context) {
        if (context.messageId() == null) return;
        String payload;
        try {
            payload = mapper.writeValueAsString(java.util.Map.of("contextId",context.id().toString(),
                    "revision",context.revision(),"state",context.state(),"expanded",context.expanded()));
        } catch (JsonProcessingException e) { throw new IllegalArgumentException("INVALID_WIDGET_SYNC",e); }
        jdbc.update("""
                INSERT INTO scheduled_notifications(id,user_id,channel,destination,fire_at,payload_type,payload,
                  widget_context_id,widget_revision,sent,retry_count)
                VALUES (?,?,'TELEGRAM',?,NOW(),'WIDGET_SYNC',?::jsonb,?,?,false,0)
                ON CONFLICT (widget_context_id) WHERE payload_type='WIDGET_SYNC' AND sent=false AND failed_at IS NULL
                DO UPDATE SET payload=EXCLUDED.payload,widget_revision=EXCLUDED.widget_revision,
                  next_attempt_at=NOW(),retry_count=0
                """,UUID.randomUUID(),context.userId(),Long.toString(context.chatId()),payload,context.id(),context.revision());
    }
    public List<WidgetContext> proposals(UUID userId, UUID proposalId) {
        return jdbc.query("SELECT * FROM telegram_widget_contexts WHERE user_id=? AND kind='PROPOSAL' AND reference_id=? AND message_id IS NOT NULL AND expires_at>NOW()",this::map,userId,proposalId);
    }
    @Transactional
    public void prune() {
        jdbc.update("DELETE FROM telegram_widget_contexts WHERE id IN (SELECT id FROM telegram_widget_contexts WHERE expires_at < NOW() - INTERVAL '7 days' LIMIT 1000)");
    }
    private WidgetContext map(ResultSet rs, int row) throws SQLException {
        try {
            return new WidgetContext(rs.getObject("id",UUID.class),rs.getObject("user_id",UUID.class),rs.getString("kind"),
                    rs.getObject("reference_id",UUID.class),rs.getObject("task_id",UUID.class),rs.getObject("deadline_snapshot",OffsetDateTime.class),
                    rs.getLong("chat_id"),rs.getObject("message_id",Long.class),rs.getString("transport"),rs.getObject("expires_at",OffsetDateTime.class),
                    rs.getLong("revision"),rs.getInt("page"),rs.getBoolean("expanded"),mapper.readValue(rs.getString("state"),WidgetState.class));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored widget",e); }
    }
}
