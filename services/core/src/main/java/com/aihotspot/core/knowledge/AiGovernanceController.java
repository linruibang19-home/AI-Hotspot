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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/ai")
@PreAuthorize("hasAuthority('ai-config:manage')")
public class AiGovernanceController {
    private final JdbcTemplate jdbc; private final KnowledgeIndexService indexer; private final ResearchService research;
    private final OutboxStore outbox; private final ObjectMapper objectMapper; private final RestClient ai;
    public AiGovernanceController(JdbcTemplate jdbc,KnowledgeIndexService indexer,ResearchService research,OutboxStore outbox,ObjectMapper objectMapper,
            @Value("${ai-hotspot.ai-base-url}") String aiBaseUrl){
        this.jdbc=jdbc;this.indexer=indexer;this.research=research;this.outbox=outbox;this.objectMapper=objectMapper;
        this.ai=RestClient.builder().baseUrl(aiBaseUrl).requestFactory(new SimpleClientHttpRequestFactory()).build();
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
    @GetMapping("/reprocess/status")
    public Map<String,Object> reprocessStatus(){
        return jdbc.queryForMap("""
            select count(*) filter(where admission_status='FAILED' and publication_status='REJECTED'
                       and lower(coalesce(provider_name,'')) in ('mock','test','fixture')) remaining_mock,
                   count(*) filter(where admission_status='PENDING' and provider_name is null) processing,
                   count(*) filter(where admission_status='PASSED' and provider_name is not null
                       and lower(provider_name) not in ('mock','test','fixture')) real_admitted,
                   count(*) filter(where publication_status='PUBLISHED' and visibility='PUBLIC') public_content
            from content.content_item
            """);
    }
    @GetMapping("/metrics") public Map<String,Object> metrics(@RequestParam(defaultValue="24") int hours){
        int windowHours=Math.max(1,Math.min(hours,168));
        List<Map<String,Object>> recent=jdbc.queryForList("select capability,provider_name,model_name,status,latency_ms,input_tokens,output_tokens,estimated_cost,error_code,recorded_at from knowledge.provider_metric where recorded_at>=now()-(? * interval '1 hour') order by recorded_at desc limit 100",windowHours);
        Map<String,Object> summary=jdbc.queryForMap("""
            select count(*) calls,coalesce(round(avg(latency_ms)),0) avg_latency_ms,
              count(*) filter(where status<>'SUCCEEDED') failures,
              coalesce(round(100.0*count(*) filter(where status<>'SUCCEEDED')/nullif(count(*),0),2),0) failure_rate,
              coalesce(sum(input_tokens),0) input_tokens,coalesce(sum(output_tokens),0) output_tokens,
              coalesce(sum(estimated_cost),0) estimated_cost,
              count(*) filter(where input_tokens>0 or output_tokens>0) usage_calls,
              count(*) filter(where estimated_cost>0) costed_calls
            from knowledge.provider_metric where recorded_at>=now()-(? * interval '1 hour')
            """,windowHours);
        Map<String,Object> rag=jdbc.queryForMap("""
            select count(*) rag_queries,
              count(*) filter(where answer_status='SUCCEEDED') succeeded,
              count(*) filter(where answer_status='NO_EVIDENCE') no_evidence,
              coalesce(round(avg(latency_ms) filter(where latency_ms is not null)),0) avg_latency_ms,
              coalesce(round((percentile_cont(0.95) within group(order by latency_ms) filter(where latency_ms is not null))::numeric),0) p95_latency_ms,
              coalesce(round(avg((retrieval_diagnostics->'stageTimingsMs'->>'embedding')::numeric)),0) avg_embedding_ms,
              coalesce(round((percentile_cont(0.95) within group(order by (retrieval_diagnostics->'stageTimingsMs'->>'embedding')::numeric) filter(where retrieval_diagnostics->'stageTimingsMs'->>'embedding' is not null))::numeric),0) p95_embedding_ms,
              coalesce(round(avg((retrieval_diagnostics->'stageTimingsMs'->>'hybridRetrieval')::numeric)),0) avg_retrieval_ms,
              coalesce(round((percentile_cont(0.95) within group(order by (retrieval_diagnostics->'stageTimingsMs'->>'hybridRetrieval')::numeric) filter(where retrieval_diagnostics->'stageTimingsMs'->>'hybridRetrieval' is not null))::numeric),0) p95_retrieval_ms,
              coalesce(round(avg((retrieval_diagnostics->'stageTimingsMs'->>'rerank')::numeric)),0) avg_rerank_ms,
              coalesce(round((percentile_cont(0.95) within group(order by (retrieval_diagnostics->'stageTimingsMs'->>'rerank')::numeric) filter(where retrieval_diagnostics->'stageTimingsMs'->>'rerank' is not null))::numeric),0) p95_rerank_ms,
              coalesce(round(avg((retrieval_diagnostics->'stageTimingsMs'->>'generation')::numeric)),0) avg_generation_ms,
              coalesce(round((percentile_cont(0.95) within group(order by (retrieval_diagnostics->'stageTimingsMs'->>'generation')::numeric) filter(where retrieval_diagnostics->'stageTimingsMs'->>'generation' is not null))::numeric),0) p95_generation_ms,
              coalesce(round(avg((retrieval_diagnostics->'stageTimingsMs'->>'citationRepair')::numeric)),0) avg_citation_repair_ms,
              coalesce(round(avg(citation_coverage)::numeric,3),0) avg_citation_coverage,
              coalesce(round(avg((retrieval_diagnostics->>'sourceCount')::numeric),2),0) avg_source_count,
              coalesce(round(avg((retrieval_diagnostics->>'contextCount')::numeric),2),0) avg_context_count,
              count(*) filter(where retrieval_diagnostics->>'conflictDetected'='true') conflict_runs,
              (select count(*) from research.evidence_assessment ea join research.citation c on c.id=ea.citation_id
                 join research.query_run qr on qr.id=c.query_run_id where qr.created_at>=now()-(? * interval '1 hour')) assessed_evidence,
              (select count(*) from research.evidence_assessment ea join research.citation c on c.id=ea.citation_id
                 join research.query_run qr on qr.id=c.query_run_id where qr.created_at>=now()-(? * interval '1 hour') and ea.evidence_stance='REFUTES') refuting_evidence,
              (select count(*) from research.evidence_assessment ea join research.citation c on c.id=ea.citation_id
                 join research.query_run qr on qr.id=c.query_run_id where qr.created_at>=now()-(? * interval '1 hour') and ea.evidence_stance='UNVERIFIED') unverified_evidence,
              (select count(*) from research.evidence_assessment ea join research.citation c on c.id=ea.citation_id
                 join research.query_run qr on qr.id=c.query_run_id where qr.created_at>=now()-(? * interval '1 hour') and ea.freshness_status='OUTDATED') outdated_evidence
            from research.query_run where created_at>=now()-(? * interval '1 hour')
            """,windowHours,windowHours,windowHours,windowHours,windowHours);
        Map<String,Object> index=jdbc.queryForMap("""
            select count(distinct d.id) filter(where d.status='INDEXED') indexed_documents,
              count(ch.id) chunks,
              count(ch.embedding) vectorized_chunks,
              count(distinct ch.source_entity_id) indexed_sources,
              count(*) filter(where ch.effective_published_at is null) missing_source_time
            from knowledge.document d left join knowledge.chunk ch on ch.document_id=d.id
            """);
        List<Map<String,Object>> providerBreakdown=jdbc.queryForList("""
            select capability,provider_name,model_name,count(*) calls,
              count(*) filter(where status<>'SUCCEEDED') failures,
              coalesce(round(100.0*count(*) filter(where status<>'SUCCEEDED')/nullif(count(*),0),2),0) error_rate,
              coalesce(round(avg(latency_ms)),0) avg_latency_ms,
              coalesce(sum(input_tokens),0) input_tokens,coalesce(sum(output_tokens),0) output_tokens,
              coalesce(sum(estimated_cost),0) estimated_cost
            from knowledge.provider_metric where recorded_at>=now()-(? * interval '1 hour')
            group by capability,provider_name,model_name order by calls desc,capability
            """,windowHours);
        List<Map<String,Object>> latencyTrend=jdbc.queryForList("""
            select to_char(date_trunc('hour',created_at),'MM-DD HH24:00') bucket,count(*) queries,
              coalesce(round(avg(latency_ms) filter(where latency_ms is not null)),0) avg_latency_ms,
              coalesce(round((percentile_cont(0.95) within group(order by latency_ms) filter(where latency_ms is not null))::numeric),0) p95_latency_ms,
              count(*) filter(where answer_status='NO_EVIDENCE') no_evidence
            from research.query_run where created_at>=now()-(? * interval '1 hour')
            group by date_trunc('hour',created_at) order by date_trunc('hour',created_at)
            """,windowHours);
        List<Map<String,Object>> noEvidenceReasons=jdbc.queryForList("""
            select coalesce(retrieval_diagnostics->>'noEvidenceReason','LEGACY_UNCLASSIFIED') reason,count(*) occurrences
            from research.query_run where created_at>=now()-(? * interval '1 hour') and answer_status='NO_EVIDENCE'
            group by coalesce(retrieval_diagnostics->>'noEvidenceReason','LEGACY_UNCLASSIFIED') order by occurrences desc
            """,windowHours);
        List<Map<String,Object>> slowQueries=jdbc.queryForList("""
            select id,question,answer_status,generation_provider,latency_ms,citation_count,citation_coverage,
              coalesce((retrieval_diagnostics->>'sourceCount')::int,0) source_count,
              coalesce((retrieval_diagnostics->'stageTimingsMs'->>'embedding')::bigint,0) embedding_ms,
              coalesce((retrieval_diagnostics->'stageTimingsMs'->>'hybridRetrieval')::bigint,0) retrieval_ms,
              coalesce((retrieval_diagnostics->'stageTimingsMs'->>'rerank')::bigint,0) rerank_ms,
              coalesce((retrieval_diagnostics->'stageTimingsMs'->>'generation')::bigint,0) generation_ms,
              created_at
            from research.query_run where created_at>=now()-(? * interval '1 hour') and latency_ms is not null
            order by (latency_ms>=12000) desc,latency_ms desc limit 20
            """,windowHours);
        boolean costConfigured=Boolean.TRUE.equals(jdbc.queryForObject("""
            select coalesce((parameters ? 'inputCostPerMillion') and (parameters ? 'outputCostPerMillion'),false)
            from knowledge.provider_config where task_type='RAG_GENERATION'
            """,Boolean.class));
        Map<String,Object> result=new java.util.LinkedHashMap<>();
        result.put("windowHours",windowHours);result.put("generatedAt",OffsetDateTime.now());
        result.put("summary",summary);result.put("rag",rag);result.put("index",index);
        result.put("providerBreakdown",providerBreakdown);result.put("latencyTrend",latencyTrend);
        result.put("noEvidenceReasons",noEvidenceReasons);result.put("slowQueries",slowQueries);
        result.put("costConfigured",costConfigured);result.put("recent",recent);
        return result;
    }
    @GetMapping("/runtime") @SuppressWarnings("unchecked") public Map<String,Object> runtime(){
        Map<String,Object> providers=ai.get().uri("/api/v1/providers").retrieve().body(Map.class);
        return providers==null?Map.of():providers;
    }
    @PostMapping("/smoke") @SuppressWarnings("unchecked") public Map<String,Object> smoke(){
        Map<String,Object> providers=runtime();
        Map<String,Object> generation=(Map<String,Object>)providers.getOrDefault("generation",Map.of());
        Map<String,Object> embedding=(Map<String,Object>)providers.getOrDefault("embedding",Map.of());
        Map<String,Object> rerank=(Map<String,Object>)providers.getOrDefault("rerank",Map.of());
        boolean realGeneration=isReal(generation); boolean realEmbedding=isReal(embedding); boolean realRerank=isReal(rerank);
        boolean generationOk=false; boolean embeddingOk=false; boolean rerankOk=false;
        Map<String,Object> evidence=new java.util.LinkedHashMap<>(); evidence.put("providers",providers);
        if(realGeneration){Map<String,Object> result=ai.post().uri("/api/v1/generate").contentType(MediaType.APPLICATION_JSON).body("{\"prompt\":\"Return only a valid JSON object with a status field set to ok.\"}").retrieve().body(Map.class);generationOk=result!=null&&!String.valueOf(result.getOrDefault("text","")).isBlank();evidence.put("generation",Map.of("ok",generationOk));}
        if(realEmbedding){Map<String,Object> result=ai.post().uri("/api/v1/embed").contentType(MediaType.APPLICATION_JSON).body("{\"texts\":[\"AI Hotspot embedding smoke test\"]}").retrieve().body(Map.class);List<Object> vectors=(List<Object>)(result==null?List.of():result.getOrDefault("vectors",List.of()));int dimensions=vectors.isEmpty()?0:((List<?>)vectors.get(0)).size();embeddingOk=!vectors.isEmpty()&&dimensions==1024;evidence.put("embedding",Map.of("ok",embeddingOk,"dimensions",dimensions));}
        if(realRerank){Map<String,Object> result=ai.post().uri("/api/v1/rerank").contentType(MediaType.APPLICATION_JSON).body("{\"query\":\"AI model\",\"documents\":[{\"id\":\"a\",\"text\":\"AI model release\"},{\"id\":\"b\",\"text\":\"weather forecast\"}],\"top_n\":1}").retrieve().body(Map.class);rerankOk=result!=null&&!((List<?>)result.getOrDefault("results",List.of())).isEmpty();evidence.put("rerank",Map.of("ok",rerankOk));}
        boolean passed=realGeneration&&realEmbedding&&realRerank&&generationOk&&embeddingOk&&rerankOk;
        evidence.put("passed",passed); evidence.put("detail",passed?"三个真实 Provider 烟测通过":"Provider 仍为 Mock 或未完整启用");
        return evidence;
    }
    @GetMapping("/evaluations") public List<Map<String,Object>> evaluations(
            @RequestParam(required=false) Boolean passed,
            @RequestParam(required=false) String status,
            @RequestParam(defaultValue="30") int limit){
        StringBuilder sql=new StringBuilder("select er.id,es.name,es.capability,er.status,(er.metrics-'cases')::text metrics,er.passed,er.started_at,er.completed_at from knowledge.evaluation_run er join knowledge.evaluation_suite es on es.id=er.suite_id where 1=1");
        List<Object> args=new java.util.ArrayList<>();
        if(passed!=null){sql.append(" and er.passed=?");args.add(passed);}
        if(status!=null&&!status.isBlank()){sql.append(" and er.status=?");args.add(status.strip().toUpperCase());}
        sql.append(" order by er.started_at desc limit ?");args.add(Math.max(1,Math.min(limit,100)));
        return jdbc.queryForList(sql.toString(),args.toArray());
    }
    @PostMapping("/evaluations/run") public Map<String,Object> evaluate(@AuthenticationPrincipal AppUserPrincipal user){
        Map<String,Object> suiteRow=jdbc.queryForMap("select id,thresholds::text thresholds from knowledge.evaluation_suite where code='RAG_BASELINE_ZH'");
        UUID suite=(UUID)suiteRow.get("id"); Map<String,Object> thresholds=fromJson(String.valueOf(suiteRow.get("thresholds"))); UUID run=UUID.randomUUID();
        long mockPublic=jdbc.queryForObject("select count(*) from content.content_item where publication_status='PUBLISHED' and visibility='PUBLIC' and (provider_name is null or lower(provider_name) in ('mock','test','fixture'))",Long.class);
        long citations=jdbc.queryForObject("select count(*) from research.citation c join research.query_run q on q.id=c.query_run_id where q.created_at>=now()-interval '7 days' and c.support_status in ('SUPPORTED','UNSUPPORTED')",Long.class);
        long supported=jdbc.queryForObject("select count(*) from research.citation c join research.query_run q on q.id=c.query_run_id where q.created_at>=now()-interval '7 days' and c.support_status='SUPPORTED'",Long.class);
        long assessedEvidence=jdbc.queryForObject("select count(*) from research.evidence_assessment ea join research.citation c on c.id=ea.citation_id join research.query_run q on q.id=c.query_run_id where q.created_at>=now()-interval '7 days'",Long.class);
        long conflictRuns=jdbc.queryForObject("select count(*) from research.query_run where created_at>=now()-interval '7 days' and retrieval_diagnostics->>'conflictDetected'='true'",Long.class);
        long indexed=jdbc.queryForObject("select count(*) from knowledge.document where status='INDEXED'",Long.class);
        long aclLeaks=jdbc.queryForObject("""
            select count(*) from knowledge.document d join knowledge.dataset ds on ds.id=d.dataset_id
            left join content.content_item c on c.id=d.content_item_id
            where d.status='INDEXED' and ds.visibility='PUBLIC' and c.id is not null
              and not(c.publication_status='PUBLISHED' and c.visibility='PUBLIC')
            """,Long.class);
        List<Map<String,Object>> cases=jdbc.queryForList("select case_key,input_data::text input_data,expected_data::text expected_data from knowledge.evaluation_case where suite_id=? order by case_key",suite);
        int hits=0; double ndcgTotal=0; List<Map<String,Object>> caseResults=new java.util.ArrayList<>();
        for(Map<String,Object> test:cases){
            Map<String,Object> input=fromJson(String.valueOf(test.get("input_data"))); Map<String,Object> expected=fromJson(String.valueOf(test.get("expected_data")));
            String query=String.valueOf(input.get("query")); int topK=Math.max(1,Math.min(number(input.get("topK"),20),50));
            Map<String,Object> filters=input.get("filters") instanceof Map<?,?> raw?raw.entrySet().stream().collect(java.util.stream.Collectors.toMap(entry->String.valueOf(entry.getKey()),Map.Entry::getValue)):Map.of();
            List<Map<String,Object>> rows=research.evaluateRetrieval(user,query,filters,topK); RagEvaluationScorer.CaseScore score=RagEvaluationScorer.score(expected,rows);
            if(score.passed())hits++; ndcgTotal+=score.ndcgGain(); caseResults.add(score.asMap(String.valueOf(test.get("case_key")),rows.size()));
        }
        double recall=cases.isEmpty()?0:(double)hits/cases.size(); double ndcg=cases.isEmpty()?0:ndcgTotal/cases.size(); double citationSupport=citations==0?0:(double)supported/citations;
        Map<String,Object> live=jdbc.queryForMap("""
            select count(*) filter(where answer_status='SUCCEEDED') live_queries,
              coalesce(avg(citation_coverage) filter(where answer_status='SUCCEEDED'),0) avg_citation_coverage,
              coalesce(avg((retrieval_diagnostics->>'sourceCount')::numeric) filter(where answer_status='SUCCEEDED'),0) avg_source_count
            from research.query_run where created_at>=now()-interval '7 days'
            """);
        long liveQueries=((Number)live.get("live_queries")).longValue();double avgCoverage=((Number)live.get("avg_citation_coverage")).doubleValue();double avgSources=((Number)live.get("avg_source_count")).doubleValue();
        int minimumCases=number(thresholds.get("minimumCases"),50); double minimumRecall=decimal(thresholds.get("recallAt20"),0.80); double minimumNdcg=decimal(thresholds.get("ndcgAt10"),0.70); double minimumCitationSupport=decimal(thresholds.get("citationSupport"),0.90);
        boolean passed=mockPublic==0&&aclLeaks<=number(thresholds.get("aclLeaks"),0)&&indexed>0&&cases.size()>=minimumCases&&recall>=minimumRecall&&ndcg>=minimumNdcg&&citationSupport>=minimumCitationSupport&&liveQueries>0&&avgCoverage>=0.80&&avgSources>=2;
        Map<String,Object> metricMap=new java.util.LinkedHashMap<>();metricMap.put("caseCount",cases.size());metricMap.put("recallAt20",recall);metricMap.put("ndcgAt10",ndcg);metricMap.put("citationSupport",citationSupport);metricMap.put("citationCount",citations);metricMap.put("assessedEvidence",assessedEvidence);metricMap.put("conflictRuns",conflictRuns);metricMap.put("indexedDocuments",indexed);metricMap.put("mockPublic",mockPublic);metricMap.put("aclLeaks",aclLeaks);metricMap.put("liveQueries",liveQueries);metricMap.put("avgCitationCoverage",avgCoverage);metricMap.put("avgSourceCount",avgSources);metricMap.put("cases",caseResults);
        jdbc.update("insert into knowledge.evaluation_run(id,suite_id,status,provider_snapshot,metrics,passed,started_by,completed_at) values(?,?,'SUCCEEDED',?::jsonb,?::jsonb,?,?,now())",run,suite,toJson(runtime()),toJson(metricMap),passed,user.id());
        return Map.of("runId",run,"passed",passed,"metrics",metricMap);
    }
    public record ConfigRequest(String taskType,String providerName,String modelName,String baseUrl,String credentialRef,String status,Integer timeoutMs,String parametersJson){}
    private static boolean isReal(Map<String,Object> provider){String name=String.valueOf(provider.getOrDefault("provider",""));return !name.isBlank()&&!List.of("mock","test","fixture").contains(name.toLowerCase());}
    private static int number(Object value,int fallback){if(value instanceof Number number)return number.intValue();try{return Integer.parseInt(String.valueOf(value));}catch(Exception ignored){return fallback;}}
    private static double decimal(Object value,double fallback){if(value instanceof Number number)return number.doubleValue();try{return Double.parseDouble(String.valueOf(value));}catch(Exception ignored){return fallback;}}
    @SuppressWarnings("unchecked") private Map<String,Object> fromJson(String value){try{return objectMapper.readValue(value,Map.class);}catch(Exception exception){return Map.of();}}
    private String toJson(Object value){try{return objectMapper.writeValueAsString(value);}catch(Exception exception){throw new IllegalStateException("无法创建重处理事件",exception);}}
}
