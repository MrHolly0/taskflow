package ru.taskflow.shared.widgets;

import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public class CheckinBatchView {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public CheckinBatchView(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc;this.clock=clock; }

    public List<Item> items(UUID user,UUID batch) {
        return jdbc.query("""
                SELECT p.id,p.task_id,p.answer,p.answer_message,p.kind,t.title,t.description,p.deadline_snapshot,u.timezone,
                  (parent.cancelled_at IS NULL AND parent.expires_at>? AND NOT t.is_deleted
                    AND t.status IN ('TODO','IN_PROGRESS') AND t.deadline IS NOT DISTINCT FROM p.deadline_snapshot
                    AND (t.planned_date IS NULL OR t.planned_date<=? OR (p.kind='WORK_CHECKIN'
                      AND (t.planned_date_set_at IS NULL OR t.planned_date_set_at<=t.started_at)))
                    AND (p.kind='DEADLINE_CHECKIN' OR ("""+ru.taskflow.shared.engagement.PassiveCheckinRules.allowedSql("t")+"""
                    ))
                    AND (p.kind<>'WORK_CHECKIN' OR (t.status='IN_PROGRESS' AND t.started_at=p.started_snapshot))) AS current
                FROM engagement_prompts parent JOIN engagement_prompts p ON p.batch_id=parent.id AND p.user_id=parent.user_id
                JOIN tasks t ON t.id=p.task_id AND t.user_id=p.user_id JOIN users u ON u.id=parent.user_id
                WHERE parent.id=? AND parent.user_id=? AND parent.kind='CHECKIN_BATCH'
                ORDER BY p.batch_position,p.id
                """,(rs,row)->new Item(rs.getObject("id",UUID.class),rs.getObject("task_id",UUID.class),rs.getString("title"),rs.getString("description"),
                        rs.getObject("deadline_snapshot",OffsetDateTime.class),ZoneId.of(rs.getString("timezone")),
                        rs.getString("answer"),rs.getBoolean("current"),rs.getString("kind"),rs.getString("answer_message")),OffsetDateTime.now(clock),OffsetDateTime.now(clock),batch,user);
    }
    public Item item(UUID user,UUID batch,int page) {
        var items=items(user,batch);
        if(page<0 || page>=items.size()) throw new IllegalArgumentException("Нет такого дела");
        return items.get(page);
    }
    public WidgetState render(UUID user,UUID batch,int requested) {
        var all=items(user,batch);
        if(all.isEmpty()) return new WidgetState("checkins","Актуальных дел больше нет",List.of()).finish("Всё разобрали!","Можно вернуться к своим планам");
        if(all.stream().noneMatch(Item::pending)) {
            long done=all.stream().filter(i->"done".equals(i.answer())).count();
            return new WidgetState("checkins","Ответы сохранены",List.of()).finish("Всё разобрали!",done>0?"Завершено дел: "+done:"Планы обновлены");
        }
        int page=Math.clamp(requested,0,all.size()-1);var item=all.get(page);
        String answer=item.answer()!=null?item.answer():item.current()?null:"stale";
        var rows=item.deadline()==null?List.<List<String>>of():List.of(List.of("Срок",WidgetTemplate.time(item.deadline(),item.zone(),clock)));
        var state=WidgetTemplate.withDescription(new WidgetState("checkins",WidgetTemplate.clip(item.title(),80),rows)
                    .checkins(page,all.size(),item.taskId().toString(),answer==null?null:item.answerMessage()!=null?item.answerMessage():answerText(answer)).fingerprint(item.id().toString()),
                item.title(),item.description());
        return state.heading("DEADLINE_CHECKIN".equals(item.kind())?"Наступил срок задачи":"Как продвигаются дела?");
    }
    public int next(UUID user,UUID batch,int page) {
        var all=items(user,batch);
        for(int step=1;step<=all.size();step++) {
            int index=(page+step)%all.size();if(all.get(index).pending()) return index;
        }
        return page;
    }
    private String answerText(String answer) {
        return switch(answer) {
            case "done" -> "Дело завершено";
            case "working" -> "Ещё в работе";
            case "later" -> "Напомню через час — срок не меняется";
            default -> "Дело уже изменилось";
        };
    }
    public record Item(UUID id,UUID taskId,String title,String description,OffsetDateTime deadline,ZoneId zone,String answer,boolean current,String kind,String answerMessage) {
        public boolean pending() { return answer==null&&current; }
    }
}
