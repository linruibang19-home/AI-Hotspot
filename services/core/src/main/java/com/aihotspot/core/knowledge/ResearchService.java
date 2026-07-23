package com.aihotspot.core.knowledge;

import com.aihotspot.core.auth.AppUserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ResearchService {
    private static final Pattern CITATION = Pattern.compile("\\[(?:C)?(\\d+)]");
    private static final Pattern DAYS = Pattern.compile("(?:最近|近|过去)\\s*(\\d{1,3})\\s*天");
    private static final double MIN_CITATION_COVERAGE = 0.80;
    private final JdbcTemplate jdbc;
    private final RestClient ai;

    public ResearchService(JdbcTemplate jdbc, @Value("${ai-hotspot.ai-base-url}") String aiBaseUrl) {
        this.jdbc = jdbc;
        this.ai = RestClient.builder().baseUrl(aiBaseUrl).requestFactory(new SimpleClientHttpRequestFactory()).build();
    }

    public List<Map<String,Object>> sessions(UUID userId) {
        return jdbc.queryForList("select id,title,status,created_at,updated_at from research.session where user_id=? order by updated_at desc limit 30", userId);
    }

    List<Map<String,Object>> evaluateRetrieval(AppUserPrincipal user, String query, int limit) {
        return evaluateRetrieval(user, query, Map.of(), limit);
    }

    List<Map<String,Object>> evaluateRetrieval(AppUserPrincipal user, String query, Map<String,Object> filters, int limit) {
        QueryPlan plan = plan(query, filters == null ? Map.of() : filters);
        return diversify(rerank(query, retrieve(user, plan, Math.max(limit * 4, 80)), Math.max(limit * 2, 40)), limit);
    }

    @Transactional
    public ResearchResult ask(AppUserPrincipal user, UUID requestedSessionId, String question, Map<String,Object> filters) {
        Instant started = Instant.now();
        Map<String,Long> stageTimings = new LinkedHashMap<>();
        long stageStarted = System.nanoTime();
        String normalized = question == null ? "" : question.strip();
        if (normalized.length() < 3 || normalized.length() > 1000) throw new IllegalArgumentException("研究问题长度必须为 3～1000 个字符");
        QueryPlan plan = plan(normalized, filters);
        stageTimings.put("queryUnderstanding",elapsedMillis(stageStarted));
        stageStarted=System.nanoTime();
        UUID sessionId = ensureSession(user, requestedSessionId, normalized);
        UUID runId = UUID.randomUUID();
        Map<String,Object> retrievalConfig = Map.of("lexicalLimit",64,"vectorLimit",64,"rerankLimit",32,"contextLimit",8,"fusion","RRF","maxPerSource",2,"maxPerEvent",1);
        jdbc.update("insert into research.query_run(id,session_id,user_id,question,normalized_query,filters,permission_snapshot,retrieval_config,query_intent) values(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb)",
                runId, sessionId, user.id(), normalized, plan.lexicalQuery(), json(filters), json(Map.of("userId",user.id(),"roles",user.roles())), json(retrievalConfig), json(plan.asMap()));
        stageTimings.put("permissionAndSession",elapsedMillis(stageStarted));

        RetrievalResult retrieval = retrieveWithTimings(user,plan,64);
        List<Map<String,Object>> candidates = retrieval.rows();
        stageTimings.put("embedding",retrieval.embeddingMs()); stageTimings.put("hybridRetrieval",retrieval.databaseMs());
        stageStarted=System.nanoTime(); List<Map<String,Object>> reranked = rerank(normalized, candidates, 32); stageTimings.put("rerank",elapsedMillis(stageStarted));
        stageStarted=System.nanoTime(); List<Map<String,Object>> evidence = diversify(reranked, 8); stageTimings.put("diversification",elapsedMillis(stageStarted));
        Map<String,Object> diagnostics = diagnostics(plan, candidates, reranked, evidence,stageTimings);
        if (evidence.isEmpty()) {
            String noEvidence = "当前有权知识库在指定时间和来源范围内没有足够证据回答该问题。";
            stageTimings.put("total",Duration.between(started,Instant.now()).toMillis()); diagnostics=diagnostics(plan,candidates,reranked,evidence,stageTimings);
            diagnostics.put("noEvidenceReason",noEvidenceReason(plan,candidates,reranked));
            jdbc.update("update research.query_run set answer_status='NO_EVIDENCE',answer=?,candidate_count=0,citation_count=0,latency_ms=?,retrieval_diagnostics=?::jsonb,completed_at=now() where id=?",
                    noEvidence, Duration.between(started,Instant.now()).toMillis(), json(diagnostics), runId);
            return new ResearchResult(runId,sessionId,noEvidence,"NO_EVIDENCE","none",List.of(),Duration.between(started,Instant.now()).toMillis(),diagnostics);
        }

        Provider provider = provider();
        PromptTemplate promptTemplate = promptTemplate();
        jdbc.update("update research.query_run set prompt_version_id=?,prompt_version=?,prompt_hash=? where id=?",
                promptTemplate.id(),promptTemplate.version(),promptTemplate.hash(),runId);
        stageStarted=System.nanoTime();
        GenerationResult generated = provider.real ? generate(normalized, plan, evidence,promptTemplate)
                : new GenerationResult(extractiveAnswer(evidence),Map.of(),0,0,false,null,true);
        String answer = normalizeGeneratedAnswer(generated.answer());
        long generationMs=elapsedMillis(stageStarted);
        stageTimings.put("generation",generationMs);
        if (provider.real) recordGenerationMetric(provider,generated,generationMs);
        stageStarted=System.nanoTime();
        double coverage = citationCoverage(answer);
        stageTimings.put("citationValidation",elapsedMillis(stageStarted));
        long repairStarted=System.nanoTime();
        boolean citationFallbackApplied = coverage < MIN_CITATION_COVERAGE
                || citedNumbers(answer).isEmpty()
                || !hasOnlyValidCitations(answer,evidence.size());
        if (citationFallbackApplied) {
            answer = extractiveAnswer(evidence);
        }
        Map<Integer,EvidenceAssessmentPolicy.Assessment> assessments = EvidenceAssessmentPolicy.assess(
                evidence,generated.assessments(),normalized,plan.days());
        EvidenceAssessmentPolicy.DisclosureResult disclosure = EvidenceAssessmentPolicy.disclose(answer,assessments);
        answer = disclosure.answer();
        coverage = citationCoverage(answer);
        stageTimings.put("citationRepair",elapsedMillis(repairStarted));
        stageStarted=System.nanoTime();
        Set<Integer> referenced = citedNumbers(answer);
        Map<Integer,EvidenceAssessmentPolicy.Assessment> referencedAssessments = new LinkedHashMap<>();
        referenced.forEach(number -> {
            EvidenceAssessmentPolicy.Assessment assessment=assessments.get(number);
            if (assessment != null) referencedAssessments.put(number,assessment);
        });
        List<Citation> citations = persistCitations(runId, evidence, referenced,assessments);
        long latency = Duration.between(started,Instant.now()).toMillis();
        stageTimings.put("persistence",elapsedMillis(stageStarted)); stageTimings.put("total",latency);
        diagnostics = diagnostics(plan,candidates,reranked,evidence,stageTimings);
        diagnostics.put("citationCoverage",coverage);
        diagnostics.put("referencedCitations",referenced.size());
        diagnostics.put("citationFallbackApplied",citationFallbackApplied);
        diagnostics.put("generationFallbackApplied",generated.failed());
        diagnostics.put("structuredOutputValid",generated.structuredOutputValid());
        diagnostics.put("promptVersion",promptTemplate.version());
        diagnostics.put("promptHash",promptTemplate.hash());
        diagnostics.put("evidenceStanceCounts",EvidenceAssessmentPolicy.stanceCounts(referencedAssessments));
        diagnostics.put("conflictDetected",disclosure.conflictDetected());
        jdbc.update("update research.query_run set answer_status='SUCCEEDED',answer=?,generation_provider=?,generation_model=?,candidate_count=?,citation_count=?,latency_ms=?,retrieval_diagnostics=?::jsonb,citation_coverage=?,completed_at=now() where id=?",
                answer,provider.name,provider.model,candidates.size(),citations.size(),latency,json(diagnostics),coverage,runId);
        jdbc.update("update research.session set updated_at=now() where id=?",sessionId);
        return new ResearchResult(runId,sessionId,answer,"SUCCEEDED",provider.name,citations,latency,diagnostics);
    }

    private UUID ensureSession(AppUserPrincipal user, UUID requested, String question) {
        if (requested == null) {
            UUID id=UUID.randomUUID();
            jdbc.update("insert into research.session(id,user_id,title) values(?,?,?)",id,user.id(),question.substring(0,Math.min(80,question.length())));
            return id;
        }
        Integer owned=jdbc.queryForObject("select count(*) from research.session where id=? and user_id=?",Integer.class,requested,user.id());
        if(owned==null||owned==0) throw new IllegalArgumentException("研究会话不存在或不可访问");
        return requested;
    }

    private List<Map<String,Object>> retrieve(AppUserPrincipal user, QueryPlan plan, int limit) {
        return retrieveWithTimings(user,plan,limit).rows();
    }

    private RetrievalResult retrieveWithTimings(AppUserPrincipal user, QueryPlan plan, int limit) {
        String roles=user.roles().stream().map(role->"'"+role.replace("'","")+"'").reduce((a,b)->a+","+b).orElse("''");
        long started=System.nanoTime(); String vector=queryEmbedding(plan.semanticQuery()); long embeddingMs=elapsedMillis(started);
        String sql="""
            with eligible as (
              select ch.id chunk_id,ch.content_text,ch.source_url,d.title,s.name source_name,
                ds.visibility dataset_visibility,ci.visibility content_visibility,ci.publication_status,ci.fact_status,
                ch.source_entity_id,ch.event_cluster_id,ch.source_official_level,ch.effective_published_at,
                coalesce(ch.authority_score,0) * coalesce(se.quality_weight,1) authority_score,
                coalesce(ch.quality_score,0) quality_score,
                coalesce(ch.final_score,0) final_score,
                case when ch.search_tsv @@ websearch_to_tsquery('simple',?)
                     then ts_rank_cd(ch.search_tsv,websearch_to_tsquery('simple',?)) else 0 end
                  + case when lower(ch.content_text) like '%'||lower(?)||'%' then 0.35 else 0 end lexical_score,
                case when ?::text is null or ch.embedding is null then 0
                     else greatest(0,1-(ch.embedding <=> ?::vector)) end semantic_score
              from knowledge.chunk ch join knowledge.document d on d.id=ch.document_id
              join knowledge.dataset ds on ds.id=d.dataset_id
              left join content.content_item ci on ci.id=d.content_item_id
              left join source.source_endpoint se on se.id=ci.endpoint_id
              left join source.source_entity s on s.id=ch.source_entity_id
              where d.status='INDEXED' and (
                ds.visibility='PUBLIC' or exists(select 1 from knowledge.dataset_acl acl
                  where acl.dataset_id=ds.id and acl.permission='READ' and (
                    (acl.principal_type='USER' and acl.principal_id=?) or
                    (acl.principal_type='ROLE' and acl.principal_id in(select id from iam.role where code in (__ROLES__))))))
                and (?::timestamptz is null or ch.effective_published_at>=?::timestamptz)
                and (not ? or ch.source_official_level in ('OFFICIAL','FIRST_PARTY'))
                and (?::text is null or ci.source_type=?::text)
            ), ranked as (
              select *,row_number() over(order by lexical_score desc,effective_published_at desc nulls last) lexical_rank,
                row_number() over(order by semantic_score desc,effective_published_at desc nulls last) semantic_rank
              from eligible where lexical_score>0 or semantic_score>0
            )
            select *,(
              case when lexical_score>0 then 1.0/(60+lexical_rank) else 0 end+
              case when semantic_score>0 then 1.0/(60+semantic_rank) else 0 end+
              final_score/100000.0+authority_score/200000.0+
              case when effective_published_at>=now()-interval '7 days' then 0.001 else 0 end
            )::numeric score
            from ranked order by score desc,effective_published_at desc nulls last,chunk_id limit ?
            """.replace("__ROLES__",roles);
        started=System.nanoTime(); List<Map<String,Object>> rows=jdbc.queryForList(sql,plan.lexicalQuery(),plan.lexicalQuery(),plan.lexicalQuery(),vector,vector,user.id(),
                plan.cutoff(),plan.cutoff(),plan.officialOnly(),plan.sourceType(),plan.sourceType(),limit);
        return new RetrievalResult(rows,embeddingMs,elapsedMillis(started));
    }

    @SuppressWarnings("unchecked")
    private String queryEmbedding(String query) {
        long started=System.nanoTime();String providerName="unavailable";String providerModel="unknown";
        try {
            Map<String,Object> providers=ai.get().uri("/api/v1/providers").retrieve().body(Map.class);
            Map<String,Object> embedding=(Map<String,Object>)providers.get("embedding");
            if(isMock(embedding.get("provider"))) return null;
            providerName=String.valueOf(embedding.get("provider"));providerModel=String.valueOf(embedding.get("model"));
            Map<String,Object> response=ai.post().uri("/api/v1/embed").contentType(MediaType.APPLICATION_JSON).body(Map.of("texts",List.of(query))).retrieve().body(Map.class);
            List<Object> vectors=(List<Object>)response.getOrDefault("vectors",List.of());
            if(vectors.isEmpty())throw new IllegalStateException("empty embedding vectors");
            List<Object> values=(List<Object>)vectors.get(0);
            if(values.size()!=1024)throw new IllegalStateException("unexpected embedding dimensions");
            recordProviderMetric("EMBEDDING",providerName,providerModel,"SUCCEEDED",elapsedMillis(started),0,0,null,BigDecimal.ZERO);
            return values.stream().map(String::valueOf).reduce("[",(left,right)->left.equals("[")?left+right:left+","+right)+"]";
        }catch(Exception error){
            if(!"unavailable".equals(providerName))recordProviderMetric("EMBEDDING",providerName,providerModel,"FAILED",elapsedMillis(started),0,0,providerErrorCode(error,"EMBEDDING"),BigDecimal.ZERO);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> rerank(String query,List<Map<String,Object>> rows,int topN) {
        if(rows.isEmpty())return rows;
        long started=System.nanoTime();String providerName="unavailable";String providerModel="unknown";
        try {
            Map<String,Object> providers=ai.get().uri("/api/v1/providers").retrieve().body(Map.class);
            Map<String,Object> config=(Map<String,Object>)providers.get("rerank");
            if(isMock(config.get("provider")))return rows.subList(0,Math.min(topN,rows.size()));
            providerName=String.valueOf(config.get("provider"));providerModel=String.valueOf(config.get("model"));
            List<Map<String,Object>> docs=rows.stream().map(row->{String text=String.valueOf(row.get("content_text"));return Map.<String,Object>of("id",String.valueOf(row.get("chunk_id")),"text",text.substring(0,Math.min(900,text.length())));}).toList();
            Map<String,Object> response=ai.post().uri("/api/v1/rerank").contentType(MediaType.APPLICATION_JSON).body(Map.of("query",query,"documents",docs,"top_n",topN)).retrieve().body(Map.class);
            Map<String,Map<String,Object>> byId=new HashMap<>(); rows.forEach(row->byId.put(String.valueOf(row.get("chunk_id")),row));
            List<Map<String,Object>> ranked=new ArrayList<>();
            for(Object value:(List<Object>)response.getOrDefault("results",List.of())){
                Map<String,Object> result=(Map<String,Object>)value; Map<String,Object> row=byId.get(String.valueOf(result.get("id")));
                if(row!=null){double external=((Number)result.getOrDefault("score",0)).doubleValue();double fused=((Number)row.get("score")).doubleValue();row.put("score",external*0.9+Math.min(1,fused*25)*0.1);ranked.add(row);}
            }
            if(ranked.isEmpty())throw new IllegalStateException("empty rerank results");
            ranked.sort((left,right)->Double.compare(((Number)right.get("score")).doubleValue(),((Number)left.get("score")).doubleValue()));
            double best=((Number)ranked.get(0).get("score")).doubleValue();double floor=Math.max(0.01,best*0.35);
            List<Map<String,Object>> qualified=ranked.stream().filter(row->((Number)row.get("score")).doubleValue()>=floor).toList();
            List<Map<String,Object>> result=qualified.isEmpty()?ranked.subList(0,Math.min(topN,ranked.size())):qualified.subList(0,Math.min(topN,qualified.size()));
            recordProviderMetric("RERANK",providerName,providerModel,"SUCCEEDED",elapsedMillis(started),0,0,null,BigDecimal.ZERO);
            return result;
        }catch(Exception error){
            if(!"unavailable".equals(providerName))recordProviderMetric("RERANK",providerName,providerModel,"FAILED",elapsedMillis(started),0,0,providerErrorCode(error,"RERANK"),BigDecimal.ZERO);
            return rows.subList(0,Math.min(topN,rows.size()));
        }
    }

    private List<Map<String,Object>> diversify(List<Map<String,Object>> rows,int limit) {
        List<Map<String,Object>> result=new ArrayList<>(); Map<Object,Integer> sourceCounts=new HashMap<>(); Set<Object> events=new HashSet<>();
        for(Map<String,Object> row:rows){Object source=row.get("source_entity_id");Object event=row.get("event_cluster_id");
            if(sourceCounts.getOrDefault(source,0)>=2)continue;if(event!=null&&!events.add(event))continue;
            result.add(row);sourceCounts.merge(source,1,Integer::sum);if(result.size()>=limit)break;}
        return result;
    }

    private QueryPlan plan(String query,Map<String,Object> filters) {
        OffsetDateTime cutoff=null;String range=String.valueOf(filters.getOrDefault("timeRange","auto"));
        Matcher matcher=DAYS.matcher(query);int days=0;
        if(matcher.find())days=Math.min(365,Integer.parseInt(matcher.group(1)));
        else if(query.contains("今天"))days=1;else if(query.contains("本周"))days=7;else if(query.contains("本月"))days=31;
        else if("7d".equals(range))days=7;else if("30d".equals(range))days=30;else if("90d".equals(range))days=90;
        if(days>0)cutoff=OffsetDateTime.now(ZoneOffset.UTC).minus(days,ChronoUnit.DAYS);
        String lexical=query.replaceAll("(?:最近|近|过去)\\s*\\d{1,3}\\s*天|今天|本周|本月|有哪些|是什么|请|总结|分析"," ").replaceAll("\\s+"," ").strip();
        if(lexical.length()<2)lexical=query;
        return new QueryPlan(lexical,query,cutoff,days,Boolean.parseBoolean(String.valueOf(filters.getOrDefault("officialOnly",false))),blankToNull(filters.get("sourceType")));
    }

    @SuppressWarnings("unchecked") private Provider provider(){try{Map<String,Object> response=ai.get().uri("/api/v1/providers").retrieve().body(Map.class);Map<String,Object> generation=(Map<String,Object>)response.get("generation");String name=String.valueOf(generation.get("provider"));return new Provider(name,String.valueOf(generation.get("model")),!isMock(name));}catch(Exception ignored){return new Provider("extractive-fallback","none",false);}}
    private PromptTemplate promptTemplate(){
        return jdbc.queryForObject("""
            select id,version,system_template,task_template,evidence_template,template_hash
            from knowledge.prompt_version
            where task_type='RAG_GENERATION' and status='ACTIVE'
            order by activated_at desc nulls last limit 1
            """,(rs,rowNum)->new PromptTemplate(
                rs.getObject("id",UUID.class),
                rs.getString("version"),
                rs.getString("system_template"),
                rs.getString("task_template"),
                rs.getString("evidence_template"),
                rs.getString("template_hash")));
    }

    @SuppressWarnings("unchecked") private GenerationResult generate(String question,QueryPlan plan,List<Map<String,Object>> evidence,PromptTemplate promptTemplate){
        StringBuilder context=new StringBuilder();for(int i=0;i<evidence.size();i++){Map<String,Object> row=evidence.get(i);String text=String.valueOf(row.get("content_text"));context.append('[').append(i+1).append("] ").append(row.get("source_name")).append(" | ").append(row.get("effective_published_at")).append(" | fact_status=").append(row.get("fact_status")).append("\n").append(text,0,Math.min(900,text.length())).append("\n\n");}
        String task=promptTemplate.taskTemplate()
                .replace("{{question}}",question)
                .replace("{{timeRange}}",plan.days()>0?"最近"+plan.days()+"天":"不限");
        String evidenceMessage=promptTemplate.evidenceTemplate().replace("{{evidence}}",context);
        try{
            Map<String,Object> response=ai.post().uri("/api/v1/generate").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("system_prompt",promptTemplate.systemTemplate(),"user_prompt",task,
                            "evidence",evidenceMessage,"max_tokens",1000)).retrieve().body(Map.class);
            GenerationResult parsed=parseGeneration(String.valueOf(response.get("text")));
            return new GenerationResult(parsed.answer(),parsed.assessments(),number(response.get("input_tokens")),
                    number(response.get("output_tokens")),false,null,parsed.structuredOutputValid());
        }catch(Exception error){
            return new GenerationResult(extractiveAnswer(evidence),Map.of(),0,0,true,
                    providerErrorCode(error,"GENERATION"),false);
        }
    }

    @SuppressWarnings("unchecked") private GenerationResult parseGeneration(String raw){
        if(raw==null||raw.isBlank())return new GenerationResult("",Map.of(),0,0,false,null,false);
        String normalized=raw.strip().replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");
        try{
            int objectStart=normalized.indexOf("{\"answer\"");
            if(objectStart<0)objectStart=normalized.indexOf('{');
            int objectEnd=normalized.lastIndexOf('}');
            String jsonObject=objectStart>=0&&objectEnd>objectStart
                    ?normalized.substring(objectStart,objectEnd+1):normalized;
            Map<String,Object> parsed=new ObjectMapper().readValue(jsonObject,Map.class);
            String answer=String.valueOf(parsed.getOrDefault("answer",""));
            Map<Integer,EvidenceAssessmentPolicy.ModelAssessment> assessments=new LinkedHashMap<>();
            Object values=parsed.get("evidenceAssessments");
            if(values instanceof List<?> list)for(Object value:list)if(value instanceof Map<?,?> item){
                int citationNo=number(item.get("citationNo"));if(citationNo<1)continue;
                assessments.put(citationNo,new EvidenceAssessmentPolicy.ModelAssessment(citationNo,
                        text(item.get("claimText"),""),text(item.get("stance"),"SUPPORTS"),
                        text(item.get("reason"),"")));
            }
            return new GenerationResult(answer,assessments,0,0,false,null,!answer.isBlank());
        }catch(Exception ignored){return new GenerationResult(raw,Map.of(),0,0,false,null,false);}
    }

    @SuppressWarnings("unchecked")
    private static String providerErrorCode(Exception error,String capability){
        if(error instanceof RestClientResponseException responseError){
            try{
                Map<String,Object> body=new ObjectMapper().readValue(responseError.getResponseBodyAsString(),Map.class);
                String code=String.valueOf(body.getOrDefault("code",""));
                if(!code.isBlank())return code;
            }catch(Exception ignored){}
            return capability+"_HTTP_"+responseError.getStatusCode().value();
        }
        return capability+"_"+error.getClass().getSimpleName().replaceAll("([a-z])([A-Z])","$1_$2").toUpperCase();
    }

    private void recordGenerationMetric(Provider provider,GenerationResult generated,long latencyMs){
        BigDecimal cost=estimatedGenerationCost(generated.inputTokens(),generated.outputTokens());
        recordProviderMetric("RAG_GENERATION",provider.name(),provider.model(),
                generated.failed()?"FAILED":"SUCCEEDED",latencyMs,generated.inputTokens(),
                generated.outputTokens(),generated.errorCode(),cost);
    }

    private void recordProviderMetric(String capability,String provider,String model,String status,
                                      long latencyMs,int inputTokens,int outputTokens,String errorCode,
                                      BigDecimal cost){
        try{
            jdbc.update("insert into knowledge.provider_metric(capability,provider_name,model_name,status,latency_ms,input_tokens,output_tokens,estimated_cost,error_code) values(?,?,?,?,?,?,?,?,?)",
                    capability,provider,model,status,latencyMs,inputTokens,outputTokens,cost,errorCode);
        }catch(Exception ignored){}
    }

    @SuppressWarnings("unchecked")
    private BigDecimal estimatedGenerationCost(int inputTokens,int outputTokens){
        try{
            String raw=jdbc.queryForObject("select parameters::text from knowledge.provider_config where task_type='RAG_GENERATION'",String.class);
            Map<String,Object> parameters=new ObjectMapper().readValue(raw==null?"{}":raw,Map.class);
            double inputRate=decimal(parameters.get("inputCostPerMillion"));
            double outputRate=decimal(parameters.get("outputCostPerMillion"));
            return BigDecimal.valueOf((inputTokens*inputRate+outputTokens*outputRate)/1_000_000d);
        }catch(Exception ignored){return BigDecimal.ZERO;}
    }

    private static String noEvidenceReason(QueryPlan plan,List<Map<String,Object>> candidates,List<Map<String,Object>> reranked){
        if(candidates.isEmpty()&&plan.officialOnly())return "OFFICIAL_SCOPE_NO_MATCH";
        if(candidates.isEmpty()&&plan.days()>0)return "TIME_RANGE_NO_MATCH";
        if(candidates.isEmpty()&&plan.sourceType()!=null)return "SOURCE_TYPE_NO_MATCH";
        if(candidates.isEmpty())return "NO_RELEVANT_CHUNK";
        if(reranked.isEmpty())return "RERANK_NO_RELEVANT";
        return "DIVERSITY_FILTERED";
    }

    private String extractiveAnswer(List<Map<String,Object>> evidence){StringBuilder answer=new StringBuilder("根据当前可访问证据，值得关注的变化如下 [1]：\n\n");for(int i=0;i<Math.min(6,evidence.size());i++){Map<String,Object> row=evidence.get(i);String text=String.valueOf(row.get("content_text")).replaceFirst("^标题：[^\\n]*\\s*原文证据：\\s*","").replaceAll("[。！？!?]+","；").replaceAll("\\s+"," ").strip();String title=String.valueOf(row.get("title"));String source=String.valueOf(row.get("source_name"));answer.append("- ").append(title).append("（").append(source).append("）：").append(text,0,Math.min(180,text.length())).append(text.length()>180?"…":"").append(" [").append(i+1).append("]\n");}return answer.toString();}
    private List<Citation> persistCitations(UUID runId, List<Map<String,Object>> evidence, Set<Integer> referenced,
                                            Map<Integer,EvidenceAssessmentPolicy.Assessment> assessments) {
        List<Citation> result = new ArrayList<>();
        for (int i = 0; i < evidence.size(); i++) {
            int citationNo = i + 1;
            if (!referenced.contains(citationNo)) continue;
            Map<String,Object> row = evidence.get(i);
            String quote = String.valueOf(row.get("content_text"));
            double score = ((Number) row.get("score")).doubleValue();
            UUID citationId=UUID.randomUUID();
            jdbc.update("insert into research.citation(id,query_run_id,chunk_id,citation_no,quote_text,retrieval_score,rerank_score,support_status) values(?,?,?,?,?,?,?,'SUPPORTED')",
                    citationId, runId, row.get("chunk_id"), citationNo,
                    quote.substring(0, Math.min(500, quote.length())), score, score);
            EvidenceAssessmentPolicy.Assessment assessment=assessments.get(citationNo);
            jdbc.update("insert into research.evidence_assessment(citation_id,claim_text,evidence_stance,freshness_status,assessment_reason,assessment_method) values(?,?,?,?,?,?)",
                    citationId,assessment.claimText(),assessment.stance(),assessment.freshnessStatus(),assessment.reason(),assessment.method());
            result.add(new Citation(citationNo, String.valueOf(row.get("title")),
                    String.valueOf(row.get("source_name")), String.valueOf(row.get("source_url")),
                    quote.substring(0, Math.min(360, quote.length())), score,
                    String.valueOf(row.get("effective_published_at")), "SUPPORTED",assessment.stance(),
                    assessment.freshnessStatus(),assessment.claimText(),assessment.reason()));
        }
        return result;
    }
    private Map<String,Object> diagnostics(QueryPlan plan,List<Map<String,Object>> candidates,List<Map<String,Object>> reranked,List<Map<String,Object>> evidence,Map<String,Long> stageTimings){Map<String,Object> map=new LinkedHashMap<>();map.put("fusion","RRF+RERANK");map.put("timeRangeDays",plan.days());map.put("candidateCount",candidates.size());map.put("rerankedCount",reranked.size());map.put("contextCount",evidence.size());map.put("sourceCount",evidence.stream().map(row->row.get("source_entity_id")).distinct().count());map.put("eventCount",evidence.stream().map(row->row.get("event_cluster_id")).filter(v->v!=null).distinct().count());map.put("officialCount",evidence.stream().filter(row->List.of("OFFICIAL","FIRST_PARTY").contains(String.valueOf(row.get("source_official_level")))).count());map.put("stageTimingsMs",new LinkedHashMap<>(stageTimings));return map;}
    private static long elapsedMillis(long startedNanos){return Math.max(0,(System.nanoTime()-startedNanos)/1_000_000);}
    private static Set<Integer> citedNumbers(String answer){Set<Integer> values=new HashSet<>();Matcher matcher=CITATION.matcher(answer);while(matcher.find())values.add(Integer.parseInt(matcher.group(1)));return values;}
    private static boolean hasOnlyValidCitations(String answer,int evidenceCount){return citedNumbers(answer).stream().allMatch(value->value>=1&&value<=evidenceCount);}
    private static double citationCoverage(String answer){String[] sentences=answer.split("[。！？!?\\n]+");int claims=0,supported=0;for(String sentence:sentences){if(sentence.strip().length()<12)continue;claims++;if(CITATION.matcher(sentence).find())supported++;}return claims==0?0:(double)supported/claims;}
    private static String normalizeGeneratedAnswer(String answer){if(answer==null)return "";return answer.strip().replaceFirst("^(?:\\[(?:C)?\\d+]\\s*[。；;，,]?\\s*)+(?=\\S)","");}
    private static boolean isMock(Object value){return List.of("mock","test","fixture").contains(String.valueOf(value).toLowerCase());}
    private static int number(Object value){if(value instanceof Number number)return number.intValue();try{return Integer.parseInt(String.valueOf(value));}catch(Exception ignored){return 0;}}
    private static double decimal(Object value){if(value instanceof Number number)return number.doubleValue();try{return Double.parseDouble(String.valueOf(value));}catch(Exception ignored){return 0;}}
    private static String text(Object value,String fallback){return value==null?fallback:String.valueOf(value);}
    private static String blankToNull(Object value){String text=value==null?"":String.valueOf(value).strip();return text.isBlank()?null:text;}
    private static String json(Object value){try{return new ObjectMapper().writeValueAsString(value);}catch(Exception ignored){return "{}";}}
    private record Provider(String name,String model,boolean real){}
    private record GenerationResult(String answer,Map<Integer,EvidenceAssessmentPolicy.ModelAssessment> assessments,
                                    int inputTokens,int outputTokens,boolean failed,String errorCode,
                                    boolean structuredOutputValid){}
    private record PromptTemplate(UUID id,String version,String systemTemplate,String taskTemplate,
                                  String evidenceTemplate,String hash){}
    private record RetrievalResult(List<Map<String,Object>> rows,long embeddingMs,long databaseMs){}
    private record QueryPlan(String lexicalQuery,String semanticQuery,OffsetDateTime cutoff,int days,boolean officialOnly,String sourceType){Map<String,Object> asMap(){Map<String,Object> map=new LinkedHashMap<>();map.put("lexicalQuery",lexicalQuery);map.put("timeRangeDays",days);map.put("cutoff",cutoff==null?null:cutoff.toString());map.put("officialOnly",officialOnly);map.put("sourceType",sourceType);return map;}}
    public record Citation(int citationNo,String title,String sourceName,String sourceUrl,String quote,double supportScore,
                           String publishedAt,String supportStatus,String evidenceStance,String freshnessStatus,
                           String claimText,String assessmentReason){}
    public record ResearchResult(UUID runId,UUID sessionId,String answer,String answerStatus,String generationProvider,List<Citation> citations,long latencyMs,Map<String,Object> diagnostics){}
}
