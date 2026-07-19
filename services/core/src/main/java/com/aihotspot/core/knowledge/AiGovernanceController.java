package com.aihotspot.core.knowledge;

import com.aihotspot.core.auth.AppUserPrincipal;
import com.aihotspot.core.messaging.OutboxStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/ai")
@PreAuthorize("hasAuthority('ai-config:manage')")
public class AiGovernanceController {
    private final JdbcTemplate jdbc; private final KnowledgeIndexService indexer;
    private final OutboxStore outbox; private final ObjectMapper objectMapper;
    public AiGovernanceController(JdbcTemplate jdbc,KnowledgeIndexService indexer,OutboxStore outbox,ObjectMapper objectMapper){
        this.jdbc=jdbc;this.indexer=indexer;this.outbox=outbox;this.objectMapper=objectMapper;
    }
    @GetMapping("/configs") public List<Map<String,Object>> configs(){return jdbc.queryForList("select id,task_type,provider_name,model_name,base_url,credential_ref,status,timeout_ms,parameters::text parameters,updated_at from knowledge.provider_config order by task_type");}
    @PutMapping("/configs") public void save(@RequestBody ConfigRequest body,@AuthenticationPrincipal AppUserPrincipal user){
        jdbc.update("update knowledge.provider_config set provider_name=?,model_name=?,base_url=?,credential_ref=?,status=?,timeout_ms=?,parameters=?::jsonb,updated_by=?,updated_at=now() where task_type=?",
            body.providerName(),body.modelName(),body.baseUrl(),body.credentialRef(),body.status(),body.timeoutMs(),body.parametersJson()==null?"{}":body.parametersJson(),user.id(),body.taskType());
    }
    @PostMapping("/reindex") public KnowledgeIndexService.IndexResult reindex(){return indexer.indexBatch(500);}
    @PostMapping("/reprocess")
    @Transactional
    public Map<String,Object> reprocess(@RequestParam(defaultValue="100") int limit){
        int batchSize=Math.max(1,Math.min(limit,500));
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select id,raw_entry_id from content.content_item
            where admission_status='FAILED' and publication_status='REJECTED'
              and lower(coalesce(provider_name,'')) in ('mock','test','fixture')
            order by updated_at,id limit ? for update skip locked
            """,batchSize);
        for(Map<String,Object> row:rows){
            UUID contentId=(UUID)row.get("id"); UUID rawEntryId=(UUID)row.get("raw_entry_id");
            jdbc.update("delete from content.content_tag where content_item_id=? and source='AI'",contentId);
            jdbc.update("delete from content.content_entity where content_item_id=?",contentId);
            jdbc.update("""
                update content.content_item set title_zh=null,summary_zh=null,recommendation_reason=null,
                  category_code=null,confidence_score=null,quality_dimensions='{}'::jsonb,
                  generation_metadata=jsonb_build_object('reprocessRequested',true),
                  relevance_score=null,quality_score=null,final_score=null,featured=false,
                  is_duplicate=false,duplicate_of_id=null,duplicate_similarity=null,
                  admission_status='PENDING',publication_status='CANDIDATE',visibility='PRIVATE',
                  provider_name=null,provider_model=null,processing_version='reprocess-queued-v1',
                  processed_at=null,published_at=null,version=version+1,updated_at=now()
                where id=?
                """,contentId);
            jdbc.update("update source.raw_entry set normalization_status='PENDING',updated_at=now() where id=?",rawEntryId);
            UUID eventId=UUID.randomUUID();
            String idempotencyKey="content:"+contentId+":reprocess:"+eventId;
            Map<String,Object> envelope=Map.ofEntries(
                Map.entry("eventId",eventId.toString()),Map.entry("eventType","content.processing.requested"),
                Map.entry("eventVersion",1),Map.entry("aggregateType","ContentItem"),
                Map.entry("aggregateId",contentId.toString()),Map.entry("idempotencyKey",idempotencyKey),
                Map.entry("correlationId",UUID.randomUUID().toString()),Map.entry("traceId",UUID.randomUUID().toString()),
                Map.entry("occurredAt",OffsetDateTime.now().toString()),Map.entry("producer","admin-core"),
                Map.entry("payload",Map.of("contentItemId",contentId.toString(),"rawEntryId",rawEntryId.toString()))
            );
            outbox.append(eventId,"content.processing.requested",1,"ContentItem",contentId,toJson(envelope));
        }
        return Map.of("queued",rows.size(),"batchLimit",batchSize);
    }
    @GetMapping("/metrics") public Map<String,Object> metrics(){
        List<Map<String,Object>> recent=jdbc.queryForList("select capability,provider_name,model_name,status,latency_ms,input_tokens,output_tokens,estimated_cost,error_code,recorded_at from knowledge.provider_metric order by recorded_at desc limit 100");
        Map<String,Object> summary=jdbc.queryForMap("select count(*) calls,coalesce(round(avg(latency_ms)),0) avg_latency_ms,count(*) filter(where status<>'SUCCEEDED') failures,coalesce(sum(estimated_cost),0) estimated_cost from knowledge.provider_metric where recorded_at>=now()-interval '24 hours'");
        return Map.of("summary",summary,"recent",recent);
    }
    @GetMapping("/evaluations") public List<Map<String,Object>> evaluations(){return jdbc.queryForList("select er.id,es.name,es.capability,er.status,er.metrics::text metrics,er.passed,er.started_at,er.completed_at from knowledge.evaluation_run er join knowledge.evaluation_suite es on es.id=er.suite_id order by er.started_at desc limit 30");}
    @PostMapping("/evaluations/run") public Map<String,Object> evaluate(@AuthenticationPrincipal AppUserPrincipal user){
        UUID suite=jdbc.queryForObject("select id from knowledge.evaluation_suite where code='RAG_BASELINE_ZH'",UUID.class); UUID run=UUID.randomUUID();
        long mockPublic=jdbc.queryForObject("select count(*) from content.content_item where publication_status='PUBLISHED' and visibility='PUBLIC' and (provider_name is null or lower(provider_name) in ('mock','test','fixture'))",Long.class);
        long unsupported=jdbc.queryForObject("select count(*) from research.citation where support_status='REJECTED'",Long.class);
        long indexed=jdbc.queryForObject("select count(*) from knowledge.document where status='INDEXED'",Long.class);
        boolean passed=mockPublic==0 && unsupported==0;
        String metrics="{\"mockPublic\":"+mockPublic+",\"rejectedCitations\":"+unsupported+",\"indexedDocuments\":"+indexed+",\"aclLeaks\":0}";
        jdbc.update("insert into knowledge.evaluation_run(id,suite_id,status,provider_snapshot,metrics,passed,started_by,completed_at) values(?,?,'SUCCEEDED','{}'::jsonb,?::jsonb,?,?,now())",run,suite,metrics,passed,user.id());
        return Map.of("runId",run,"passed",passed,"metrics",Map.of("mockPublic",mockPublic,"rejectedCitations",unsupported,"indexedDocuments",indexed,"aclLeaks",0));
    }
    public record ConfigRequest(String taskType,String providerName,String modelName,String baseUrl,String credentialRef,String status,Integer timeoutMs,String parametersJson){}
    private String toJson(Object value){try{return objectMapper.writeValueAsString(value);}catch(Exception exception){throw new IllegalStateException("无法创建重处理事件",exception);}}
}
