package com.aihotspot.core.subscription;

import com.aihotspot.core.notification.MailProvider;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {
    private final JdbcTemplate jdbc; private final MailProvider mail; private final byte[] secret; private final String publicBaseUrl;
    public SubscriptionService(JdbcTemplate jdbc,MailProvider mail,@Value("${ai-hotspot.mail.unsubscribe-secret:local-change-me}") String secret,
            @Value("${ai-hotspot.public-base-url:http://127.0.0.1:3000}") String publicBaseUrl){this.jdbc=jdbc;this.mail=mail;this.secret=secret.getBytes(StandardCharsets.UTF_8);this.publicBaseUrl=publicBaseUrl;}

    public List<Map<String,Object>> list(UUID userId){return jdbc.queryForList("select id,name,frequency,timezone,send_time,weekday,coalesce(array_to_json(topic_slugs),'[]'::json)::text topic_slugs,coalesce(array_to_json(source_levels),'[]'::json)::text source_levels,coalesce(array_to_json(source_entity_ids),'[]'::json)::text source_entity_ids,coalesce(array_to_json(content_types),'[]'::json)::text content_types,max_items,status,next_run_at,last_run_at,created_at from automation.subscription where user_id=? order by created_at desc",userId);}
    public Map<String,Object> options(){return Map.of(
            "topics",jdbc.queryForList("select slug,name from content.topic where status='ACTIVE' order by name"),
            "sources",jdbc.queryForList("select id,name,official_level from source.source_entity where status='ACTIVE' order by official_level,name"));}
    public List<Map<String,Object>> deliveries(){return jdbc.queryForList("select d.id,r.subscription_id,s.name,u.email::text email,d.status,d.attempt_count,d.last_error,d.sent_at,d.next_retry_at,d.created_at from automation.email_delivery d join automation.subscription_run r on r.id=d.subscription_run_id join automation.subscription s on s.id=r.subscription_id join iam.user_account u on u.id=d.user_id order by d.created_at desc limit 100");}
    @Transactional public void retry(UUID deliveryId){Map<String,Object> row=jdbc.queryForMap("select r.subscription_id from automation.email_delivery d join automation.subscription_run r on r.id=d.subscription_run_id where d.id=? and d.status='FAILED'",deliveryId);Map<String,Object> subscription=jdbc.queryForMap("select * from automation.subscription where id=?",row.get("subscription_id"));deliver(subscription);}
    @Transactional
    public UUID create(UUID userId,SubscriptionRequest request){
        UUID id=UUID.randomUUID(); String frequency=normalizedFrequency(request.frequency()); String timezone=request.timezone()==null?"Asia/Shanghai":request.timezone(); LocalTime time=request.sendTime()==null?LocalTime.of(9,0):request.sendTime();
        jdbc.update("insert into automation.subscription(id,user_id,name,frequency,timezone,send_time,weekday,topic_slugs,source_levels,source_entity_ids,content_types,max_items,status,next_run_at) values(?,?,?,?,?,?,?,?::text[],?::text[],?::uuid[],?::text[],?,'ACTIVE',?)",
                id,userId,request.name(),frequency,timezone,time,"WEEKLY".equals(frequency)?(request.weekday()==null?1:request.weekday()):null,
                textArray(request.topicSlugs()),textArray(defaultSourceLevels(request.sourceLevels())),uuidArray(request.sourceEntityIds()),textArray(request.contentTypes()),
                maxItems(request.maxItems()),nextRun(frequency,timezone,time,request.weekday()).toOffsetDateTime());
        return id;
    }
    @Transactional public void update(UUID userId,UUID id,SubscriptionRequest request){
        String frequency=normalizedFrequency(request.frequency());String timezone=request.timezone()==null?"Asia/Shanghai":request.timezone();LocalTime time=request.sendTime()==null?LocalTime.of(9,0):request.sendTime();Integer weekday="WEEKLY".equals(frequency)?(request.weekday()==null?1:request.weekday()):null;
        int changed=jdbc.update("update automation.subscription set name=?,frequency=?,timezone=?,send_time=?,weekday=?,topic_slugs=?::text[],source_levels=?::text[],source_entity_ids=?::uuid[],content_types=?::text[],max_items=?,next_run_at=?,updated_at=now() where id=? and user_id=?",
                request.name(),frequency,timezone,time,weekday,textArray(request.topicSlugs()),textArray(defaultSourceLevels(request.sourceLevels())),uuidArray(request.sourceEntityIds()),textArray(request.contentTypes()),maxItems(request.maxItems()),nextRun(frequency,timezone,time,weekday).toOffsetDateTime(),id,userId);
        if(changed==0)throw new IllegalArgumentException("订阅不存在或无权编辑");
    }
    @Transactional public void delete(UUID userId,UUID id){if(jdbc.update("delete from automation.subscription where id=? and user_id=?",id,userId)==0)throw new IllegalArgumentException("订阅不存在或无权删除");}
    @Transactional public void setStatus(UUID userId,UUID id,String status){
        if(!List.of("ACTIVE","PAUSED","UNSUBSCRIBED").contains(status))throw new IllegalArgumentException("无效订阅状态");
        jdbc.update("update automation.subscription set status=?,updated_at=now() where id=? and user_id=?",status,id,userId);
    }
    public Map<String,Object> sendNow(UUID userId,UUID id){Map<String,Object> subscription=jdbc.queryForMap("select * from automation.subscription where id=? and user_id=?",id,userId);int itemCount=deliver(subscription);return Map.of("status",itemCount==0?"SKIPPED":"SENT","itemCount",itemCount);}
    public List<Map<String,Object>> preview(UUID userId,UUID id){owned(userId,id);return eligibleItems(id);}
    public List<Map<String,Object>> deliveryHistory(UUID userId,UUID id){owned(userId,id);return jdbc.queryForList("""
            select d.id,d.status,d.attempt_count,d.provider_name,d.subject,d.last_error,d.sent_at,d.created_at,
              r.status run_status,r.item_count,r.scheduled_at,r.completed_at
            from automation.subscription_run r
            left join automation.email_delivery d on d.subscription_run_id=r.id
            where r.subscription_id=? order by r.started_at desc limit 30
            """,id);}

    @Scheduled(fixedDelayString="${ai-hotspot.subscription.fixed-delay:60000}",initialDelayString="${ai-hotspot.subscription.initial-delay:30000}")
    public void deliverDue(){for(Map<String,Object> row:jdbc.queryForList("select * from automation.subscription where status='ACTIVE' and next_run_at<=now() order by next_run_at limit 20")){try{deliver(row);}catch(Exception ignored){}}}

    @Transactional
    public int deliver(Map<String,Object> subscription){
        UUID subscriptionId=(UUID)subscription.get("id"); UUID userId=(UUID)subscription.get("user_id"); UUID runId=UUID.randomUUID();
        jdbc.update("insert into automation.subscription_run(id,subscription_id,scheduled_at,status) values(?,?,now(),'RUNNING') on conflict(subscription_id,scheduled_at) do nothing",runId,subscriptionId);
        String email=jdbc.queryForObject("select email::text from iam.user_account where id=?",String.class,userId);
        List<Map<String,Object>> items=eligibleItems(subscriptionId);
        if(items.isEmpty()){
            jdbc.update("update automation.subscription_run set status='SKIPPED',error_message='NO_ELIGIBLE_REAL_CONTENT',completed_at=now() where id=?",runId);
            String frequency=String.valueOf(subscription.get("frequency"));String timezone=String.valueOf(subscription.get("timezone"));LocalTime time=asLocalTime(subscription.get("send_time"));Integer weekday=subscription.get("weekday")==null?null:((Number)subscription.get("weekday")).intValue();
            jdbc.update("update automation.subscription set last_run_at=now(),next_run_at=?,updated_at=now() where id=?",nextRun(frequency,timezone,time,weekday).toOffsetDateTime(),subscriptionId);
            return 0;
        }
        UUID deliveryId=UUID.randomUUID();String subject="AI Hotspot · "+subscription.get("name");
        jdbc.update("insert into automation.email_delivery(id,subscription_run_id,user_id,recipient_email,provider_name,subject,status,attempt_count) values(?,?,?,?,?,?,'SENDING',1)",deliveryId,runId,userId,email,mail.providerName(),subject);
        try{
            mail.send(email,subject,html(subscriptionId,items));
            jdbc.update("update automation.email_delivery set status='SENT',sent_at=now() where id=?",deliveryId);
            jdbc.update("update automation.subscription_run set status='SUCCEEDED',item_count=?,completed_at=now() where id=?",items.size(),runId);
            String frequency=String.valueOf(subscription.get("frequency"));String timezone=String.valueOf(subscription.get("timezone"));LocalTime time=asLocalTime(subscription.get("send_time"));Integer weekday=subscription.get("weekday")==null?null:((Number)subscription.get("weekday")).intValue();
            jdbc.update("update automation.subscription set last_run_at=now(),next_run_at=?,updated_at=now() where id=?",nextRun(frequency,timezone,time,weekday).toOffsetDateTime(),subscriptionId);
            return items.size();
        }catch(Exception error){jdbc.update("update automation.email_delivery set status='FAILED',last_error=?,next_retry_at=now()+interval '5 minutes' where id=?",error.getMessage(),deliveryId);jdbc.update("update automation.subscription_run set status='FAILED',error_message=?,completed_at=now() where id=?",error.getMessage(),runId);throw new IllegalStateException("邮件投递失败",error);}
    }
    @Transactional public boolean unsubscribe(String token,String reason){UUID id=verify(token);if(id==null)return false;Map<String,Object> row=jdbc.queryForMap("select user_id from automation.subscription where id=?",id);jdbc.update("update automation.subscription set status='UNSUBSCRIBED',updated_at=now() where id=?",id);jdbc.update("insert into automation.unsubscribe_event(id,subscription_id,user_id,reason) values(?,?,?,?)",UUID.randomUUID(),id,row.get("user_id"),reason);return true;}
    private String html(UUID id,List<Map<String,Object>> items){StringBuilder out=new StringBuilder("<h1>AI Hotspot 简报</h1><p>仅包含经过真实模型审核的公开内容。</p>");for(Map<String,Object> item:items)out.append("<h2>").append(escape(String.valueOf(item.get("title")))).append("</h2><p>").append(escape(String.valueOf(item.get("summary_zh")))).append("</p><small>").append(escape(String.valueOf(item.get("source_name")))).append("</small>");out.append("<hr><a href=\"").append(publicBaseUrl).append("/api/core/api/v1/public/unsubscribe/").append(token(id)).append("\">一键退订</a> · <a href=\"").append(publicBaseUrl).append("/subscriptions\">订阅偏好</a>");return out.toString();}
    private String token(UUID id){String payload=id.toString();return Base64.getUrlEncoder().withoutPadding().encodeToString((payload+"."+sign(payload)).getBytes(StandardCharsets.UTF_8));}
    private UUID verify(String token){try{String value=new String(Base64.getUrlDecoder().decode(token),StandardCharsets.UTF_8);int dot=value.indexOf('.');String payload=value.substring(0,dot);if(!java.security.MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),value.substring(dot+1).getBytes(StandardCharsets.UTF_8)))return null;return UUID.fromString(payload);}catch(Exception error){return null;}}
    private String sign(String payload){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
    private static ZonedDateTime nextRun(String frequency,String timezone,LocalTime time,Integer weekday){ZoneId zone=ZoneId.of(timezone);ZonedDateTime now=ZonedDateTime.now(zone);LocalDate date=now.toLocalDate();if("WEEKLY".equals(frequency)){DayOfWeek target=DayOfWeek.of(weekday==null?1:weekday);while(date.getDayOfWeek()!=target||!date.atTime(time).atZone(zone).isAfter(now))date=date.plusDays(1);}else if(!date.atTime(time).atZone(zone).isAfter(now))date=date.plusDays(1);return date.atTime(time).atZone(zone);}
    private static String normalizedFrequency(String value){String frequency=value==null?"DAILY":value.toUpperCase();if(!List.of("DAILY","WEEKLY").contains(frequency))throw new IllegalArgumentException("只支持 DAILY 或 WEEKLY");return frequency;}
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static String textArray(List<String> values){if(values==null||values.isEmpty())return "{}";return values.stream().map(value->"\""+value.replace("\"","\\\"")+"\"").reduce("{",(left,right)->left.equals("{")?left+right:left+","+right)+"}";}
    private static String uuidArray(List<UUID> values){return values==null||values.isEmpty()?"{}":values.stream().map(UUID::toString).reduce("{",(left,right)->left.equals("{")?left+right:left+","+right)+"}";}
    private static List<String> defaultSourceLevels(List<String> values){return values==null||values.isEmpty()?List.of("OFFICIAL","FIRST_PARTY","THIRD_PARTY"):values;}
    private static int maxItems(Integer value){return value==null?12:Math.min(50,Math.max(1,value));}
    private void owned(UUID userId,UUID id){Integer count=jdbc.queryForObject("select count(*) from automation.subscription where id=? and user_id=?",Integer.class,id,userId);if(count==null||count==0)throw new IllegalArgumentException("订阅不存在或无权访问");}
    private List<Map<String,Object>> eligibleItems(UUID subscriptionId){return jdbc.queryForList("""
          select c.id,coalesce(c.title_zh,c.original_title) title,c.summary_zh,s.name source_name,c.canonical_url,c.content_type,c.effective_published_at
          from automation.subscription sub
          join content.content_item c on true
          join source.source_entity s on s.id=c.source_entity_id
          where sub.id=? and c.publication_status='PUBLISHED' and c.visibility='PUBLIC' and c.admission_status='PASSED'
            and c.is_duplicate=false and c.fact_status<>'DEBUNKED' and c.provider_name is not null
            and lower(c.provider_name) not in ('mock','test','fixture')
            and (cardinality(sub.source_levels)=0 or s.official_level=any(sub.source_levels))
            and (cardinality(sub.source_entity_ids)=0 or c.source_entity_id=any(sub.source_entity_ids))
            and (cardinality(sub.content_types)=0 or c.content_type=any(sub.content_types))
            and (cardinality(sub.topic_slugs)=0 or exists(
              select 1 from content.content_topic ct join content.topic t on t.id=ct.topic_id
              where ct.content_item_id=c.id and t.slug=any(sub.topic_slugs)))
          order by c.featured desc,c.final_score desc nulls last,c.effective_published_at desc
          limit (select max_items from automation.subscription where id=?)
          """,subscriptionId,subscriptionId);}
    private static LocalTime asLocalTime(Object value){if(value instanceof LocalTime time)return time;if(value instanceof java.sql.Time time)return time.toLocalTime();return LocalTime.parse(String.valueOf(value));}
    public record SubscriptionRequest(String name,String frequency,String timezone,LocalTime sendTime,Integer weekday,List<String> topicSlugs,List<String> sourceLevels,List<UUID> sourceEntityIds,List<String> contentTypes,Integer maxItems){}
}
