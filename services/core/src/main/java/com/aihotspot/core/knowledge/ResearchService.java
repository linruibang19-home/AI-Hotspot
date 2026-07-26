package com.aihotspot.core.knowledge;

import com.aihotspot.core.auth.AppUserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private static final double MIN_CITATION_COVERAGE = 0.80;
    private final JdbcTemplate jdbc;
    private final RestClient ai;
    private final ResearchFeedbackService feedback;
    private final ProviderConnectionService providerConnections;
    private final String aiInternalToken;

    public ResearchService(JdbcTemplate jdbc, ResearchFeedbackService feedback,
                           ProviderConnectionService providerConnections,
                           @Value("${ai-hotspot.ai-base-url}") String aiBaseUrl,
                           @Value("${ai-hotspot.security.ai-internal-token:ai-hotspot-local-internal-token}") String aiInternalToken) {
        this.jdbc = jdbc;
        this.feedback = feedback;
        this.providerConnections = providerConnections;
        this.aiInternalToken = aiInternalToken;
        this.ai = RestClient.builder().baseUrl(aiBaseUrl).requestFactory(new SimpleClientHttpRequestFactory()).build();
    }

    public List<ResearchSessionSummary> sessions(UUID userId) {
        return jdbc.query("""
            select s.id,s.title,s.status,s.created_at,s.updated_at,
              latest.question latest_question,latest.answer_status latest_answer_status,
              (select count(*) from research.query_run q where q.session_id=s.id) query_count
            from research.session s
            left join lateral (
              select question,answer_status from research.query_run
              where session_id=s.id order by created_at desc limit 1
            ) latest on true
            where s.user_id=? order by s.updated_at desc limit 30
            """,(rs,row) -> new ResearchSessionSummary(
                rs.getObject("id",UUID.class),rs.getString("title"),rs.getString("status"),
                rs.getObject("created_at",OffsetDateTime.class),rs.getObject("updated_at",OffsetDateTime.class),
                rs.getString("latest_question"),rs.getString("latest_answer_status"),rs.getInt("query_count")),userId);
    }

    public ResearchSessionView session(UUID userId,UUID sessionId) {
        List<ResearchSessionSummary> summaries=jdbc.query("""
            select s.id,s.title,s.status,s.created_at,s.updated_at,
              latest.question latest_question,latest.answer_status latest_answer_status,
              (select count(*) from research.query_run q where q.session_id=s.id) query_count
            from research.session s
            left join lateral (
              select question,answer_status from research.query_run
              where session_id=s.id order by created_at desc limit 1
            ) latest on true
            where s.id=? and s.user_id=?
            """,(rs,row) -> new ResearchSessionSummary(
                rs.getObject("id",UUID.class),rs.getString("title"),rs.getString("status"),
                rs.getObject("created_at",OffsetDateTime.class),rs.getObject("updated_at",OffsetDateTime.class),
                rs.getString("latest_question"),rs.getString("latest_answer_status"),rs.getInt("query_count")),
                sessionId,userId);
        if(summaries.isEmpty())throw new IllegalArgumentException("研究会话不存在或不可访问");
        List<ResearchTurn> turns=jdbc.query("""
            select id,question,answer,answer_status,generation_provider,latency_ms,
              retrieval_diagnostics,created_at
            from research.query_run where session_id=? and user_id=?
            order by created_at
            """,(rs,row) -> {
                UUID runId=rs.getObject("id",UUID.class);
                return new ResearchTurn(runId,rs.getString("question"),rs.getString("answer"),
                        rs.getString("answer_status"),rs.getString("generation_provider"),
                        citations(runId),rs.getLong("latency_ms"),
                        jsonMap(rs.getObject("retrieval_diagnostics")),
                        rs.getObject("created_at",OffsetDateTime.class),
                        feedback.findForRun(userId,runId));
            },sessionId,userId);
        return new ResearchSessionView(summaries.get(0),turns);
    }

    List<Map<String,Object>> evaluateRetrieval(AppUserPrincipal user, String query, int limit) {
        return evaluateRetrieval(user, query, Map.of(), limit);
    }

    List<Map<String,Object>> evaluateRetrieval(AppUserPrincipal user, String query, Map<String,Object> filters, int limit) {
        RagQueryPlanner.Plan plan = RagQueryPlanner.plan(query, filters == null ? Map.of() : filters);
        return diversify(rerank(plan.semanticQuery(), retrieve(user, plan, Math.max(limit * 4, 80)),
                Math.max(limit * 2, 40),0,user.id(),null), limit);
    }

    @Transactional
    public ResearchResult ask(AppUserPrincipal user, UUID requestedSessionId, String question, Map<String,Object> filters) {
        Instant started = Instant.now();
        Map<String,Long> stageTimings = new LinkedHashMap<>();
        long stageStarted = System.nanoTime();
        String normalized = question == null ? "" : question.strip();
        if (normalized.length() < 3 || normalized.length() > 1000) throw new IllegalArgumentException("研究问题长度必须为 3～1000 个字符");
        RagQueryPlanner.Plan plan = RagQueryPlanner.plan(normalized, filters);
        stageTimings.put("queryUnderstanding",elapsedMillis(stageStarted));
        stageStarted=System.nanoTime();
        UUID sessionId = ensureSession(user, requestedSessionId, normalized);
        UUID runId = UUID.randomUUID();
        boolean broadWeeklyReport = "WEEKLY_REPORT".equals(plan.intent());
        int minimumRerankResults = broadWeeklyReport ? 32 : 0;
        int maxPerSource = broadWeeklyReport ? 1 : 2;
        Map<String,Object> retrievalConfig = Map.of(
                "lexicalLimit",64,
                "vectorLimit",64,
                "rerankLimit",32,
                "contextLimit",8,
                "fusion","RRF",
                "maxPerSource",maxPerSource,
                "maxPerEvent",1,
                "minimumRerankResults",minimumRerankResults,
                "queryPlannerVersion","temporal-v2");
        jdbc.update("insert into research.query_run(id,session_id,user_id,question,normalized_query,filters,permission_snapshot,retrieval_config,query_intent) values(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb)",
                runId, sessionId, user.id(), normalized, plan.lexicalQuery(), json(filters), json(Map.of("userId",user.id(),"roles",user.roles())), json(retrievalConfig), json(plan.asMap()));
        stageTimings.put("permissionAndSession",elapsedMillis(stageStarted));

        RetrievalResult retrieval = retrieveWithTimings(user,plan,64,runId);
        List<Map<String,Object>> candidates = retrieval.rows();
        stageTimings.put("embedding",retrieval.embeddingMs()); stageTimings.put("hybridRetrieval",retrieval.databaseMs());
        stageStarted=System.nanoTime(); List<Map<String,Object>> reranked = rerank(
                plan.semanticQuery(), candidates, 32, minimumRerankResults,user.id(),runId);
        stageTimings.put("rerank",elapsedMillis(stageStarted));
        stageStarted=System.nanoTime(); List<Map<String,Object>> evidence = diversify(reranked, 8, maxPerSource); stageTimings.put("diversification",elapsedMillis(stageStarted));
        Map<String,Object> diagnostics = diagnostics(plan, candidates, reranked, evidence,stageTimings);
        if (evidence.isEmpty()) {
            String noEvidence = "当前有权知识库在指定时间和来源范围内没有足够证据回答该问题。";
            stageTimings.put("total",Duration.between(started,Instant.now()).toMillis()); diagnostics=diagnostics(plan,candidates,reranked,evidence,stageTimings);
            diagnostics.put("noEvidenceReason",noEvidenceReason(plan,candidates,reranked));
            jdbc.update("update research.query_run set answer_status='NO_EVIDENCE',answer=?,candidate_count=0,citation_count=0,latency_ms=?,retrieval_diagnostics=?::jsonb,completed_at=now() where id=?",
                    noEvidence, Duration.between(started,Instant.now()).toMillis(), json(diagnostics), runId);
            return new ResearchResult(runId,sessionId,noEvidence,"NO_EVIDENCE","none",List.of(),Duration.between(started,Instant.now()).toMillis(),diagnostics,0,0,BigDecimal.ZERO,null);
        }

        Provider provider = provider(user.id());
        PromptTemplate promptTemplate = promptTemplate();
        jdbc.update("update research.query_run set prompt_version_id=?,prompt_version=?,prompt_hash=? where id=?",
                promptTemplate.id(),promptTemplate.version(),promptTemplate.hash(),runId);
        stageStarted=System.nanoTime();
        GenerationResult generated = provider.real ? generate(normalized, plan, evidence,promptTemplate,provider)
                : new GenerationResult(extractiveAnswer(evidence),Map.of(),0,0,false,null,true);
        String answer = normalizeGeneratedAnswer(generated.answer());
        boolean structuredFallbackApplied = provider.real && !generated.structuredOutputValid();
        if (structuredFallbackApplied) answer = extractiveAnswer(evidence);
        long generationMs=elapsedMillis(stageStarted);
        stageTimings.put("generation",generationMs);
        if (provider.real) recordGenerationMetric(provider,generated,generationMs,user.id(),runId);
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
        diagnostics.put("generationFallbackApplied",generated.failed() || structuredFallbackApplied);
        diagnostics.put("structuredOutputValid",generated.structuredOutputValid());
        diagnostics.put("promptVersion",promptTemplate.version());
        diagnostics.put("promptHash",promptTemplate.hash());
        diagnostics.put("evidenceStanceCounts",EvidenceAssessmentPolicy.stanceCounts(referencedAssessments));
        diagnostics.put("claimEvidenceValidated",referencedAssessments.values().stream()
                .filter(value -> !"NOT_EVALUATED".equals(value.entailmentStatus())).count());
        diagnostics.put("claimEvidenceRejected",referencedAssessments.values().stream()
                .filter(value -> "UNSUPPORTED".equals(value.entailmentStatus())).count());
        diagnostics.put("conflictDetected",disclosure.conflictDetected());
        jdbc.update("update research.query_run set answer_status='SUCCEEDED',answer=?,generation_provider=?,generation_model=?,candidate_count=?,citation_count=?,latency_ms=?,retrieval_diagnostics=?::jsonb,citation_coverage=?,completed_at=now() where id=?",
                answer,provider.name,provider.model,candidates.size(),citations.size(),latency,json(diagnostics),coverage,runId);
        jdbc.update("update research.session set updated_at=now() where id=?",sessionId);
        return new ResearchResult(runId,sessionId,answer,"SUCCEEDED",provider.name,citations,latency,
                diagnostics,generated.inputTokens(),generated.outputTokens(),
                estimatedGenerationCost(provider.parametersJson(),generated.inputTokens(),generated.outputTokens()),null);
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

    private List<Map<String,Object>> retrieve(AppUserPrincipal user, RagQueryPlanner.Plan plan, int limit) {
        return retrieveWithTimings(user,plan,limit,null).rows();
    }

    private RetrievalResult retrieveWithTimings(AppUserPrincipal user, RagQueryPlanner.Plan plan, int limit,
                                                UUID queryRunId) {
        String roles=user.roles().stream().map(role->"'"+role.replace("'","")+"'").reduce((a,b)->a+","+b).orElse("''");
        long started=System.nanoTime(); String vector=queryEmbedding(plan.semanticQuery(),user.id(),queryRunId); long embeddingMs=elapsedMillis(started);
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
                and (?::timestamptz is null or ch.effective_published_at<=?::timestamptz)
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
                plan.windowStart(),plan.windowStart(),plan.windowEnd(),plan.windowEnd(),
                plan.officialOnly(),plan.sourceType(),plan.sourceType(),limit);
        return new RetrievalResult(rows,embeddingMs,elapsedMillis(started));
    }

    @SuppressWarnings("unchecked")
    private String queryEmbedding(String query,UUID actorUserId,UUID queryRunId) {
        long started=System.nanoTime();String providerName="unavailable";String providerModel="unknown";
        ProviderConnectionService.RuntimeSelection selection=providerConnections.selection("EMBEDDING",actorUserId);
        try {
            if(selection.dynamic()){
                providerName=selection.provider();providerModel=selection.model();
            }else{
                Map<String,Object> providers=ai.get().uri("/api/v1/providers").retrieve().body(Map.class);
                Map<String,Object> embedding=(Map<String,Object>)providers.get("embedding");
                if(isMock(embedding.get("provider"))) return null;
                providerName=String.valueOf(embedding.get("provider"));providerModel=String.valueOf(embedding.get("model"));
            }
            Map<String,Object> body=new LinkedHashMap<>();body.put("texts",List.of(query));
            if(selection.dynamic())body.put("provider_override",selection.override());
            RestClient.RequestBodySpec request=ai.post().uri("/api/v1/embed").contentType(MediaType.APPLICATION_JSON);
            if(selection.dynamic())request.header("X-AI-Internal-Token",aiInternalToken);
            Map<String,Object> response=request.body(body).retrieve().body(Map.class);
            List<Object> vectors=(List<Object>)response.getOrDefault("vectors",List.of());
            if(vectors.isEmpty())throw new IllegalStateException("empty embedding vectors");
            List<Object> values=(List<Object>)vectors.get(0);
            if(values.size()!=1024)throw new IllegalStateException("unexpected embedding dimensions");
            recordProviderMetric("EMBEDDING",providerName,providerModel,"SUCCEEDED",elapsedMillis(started),
                    0,0,null,BigDecimal.ZERO,selection.connectionId(),actorUserId,queryRunId,selection.scope());
            return values.stream().map(String::valueOf).reduce("[",(left,right)->left.equals("[")?left+right:left+","+right)+"]";
        }catch(Exception error){
            if(!"unavailable".equals(providerName))recordProviderMetric("EMBEDDING",providerName,providerModel,
                    "FAILED",elapsedMillis(started),0,0,providerErrorCode(error,"EMBEDDING"),
                    BigDecimal.ZERO,selection.connectionId(),actorUserId,queryRunId,selection.scope());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> rerank(String query,List<Map<String,Object>> rows,int topN) {
        return rerank(query,rows,topN,0,null,null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> rerank(String query,List<Map<String,Object>> rows,int topN,int minimumResults,
                                           UUID actorUserId,UUID queryRunId) {
        if(rows.isEmpty())return rows;
        long started=System.nanoTime();String providerName="unavailable";String providerModel="unknown";
        ProviderConnectionService.RuntimeSelection selection=providerConnections.selection("RERANK",actorUserId);
        try {
            if(selection.dynamic()){
                providerName=selection.provider();providerModel=selection.model();
            }else{
                Map<String,Object> providers=ai.get().uri("/api/v1/providers").retrieve().body(Map.class);
                Map<String,Object> config=(Map<String,Object>)providers.get("rerank");
                if(isMock(config.get("provider")))return rows.subList(0,Math.min(topN,rows.size()));
                providerName=String.valueOf(config.get("provider"));providerModel=String.valueOf(config.get("model"));
            }
            List<Map<String,Object>> docs=rows.stream().map(row->{String text=String.valueOf(row.get("content_text"));return Map.<String,Object>of("id",String.valueOf(row.get("chunk_id")),"text",text.substring(0,Math.min(900,text.length())));}).toList();
            Map<String,Object> body=new LinkedHashMap<>();body.put("query",query);body.put("documents",docs);body.put("top_n",topN);
            if(selection.dynamic())body.put("provider_override",selection.override());
            RestClient.RequestBodySpec request=ai.post().uri("/api/v1/rerank").contentType(MediaType.APPLICATION_JSON);
            if(selection.dynamic())request.header("X-AI-Internal-Token",aiInternalToken);
            Map<String,Object> response=request.body(body).retrieve().body(Map.class);
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
            int resultSize=Math.min(topN,Math.max(Math.min(minimumResults,ranked.size()),qualified.size()));
            List<Map<String,Object>> result=ranked.subList(0,resultSize);
            recordProviderMetric("RERANK",providerName,providerModel,"SUCCEEDED",elapsedMillis(started),
                    0,0,null,BigDecimal.ZERO,selection.connectionId(),actorUserId,queryRunId,selection.scope());
            return result;
        }catch(Exception error){
            if(!"unavailable".equals(providerName))recordProviderMetric("RERANK",providerName,providerModel,
                    "FAILED",elapsedMillis(started),0,0,providerErrorCode(error,"RERANK"),
                    BigDecimal.ZERO,selection.connectionId(),actorUserId,queryRunId,selection.scope());
            return rows.subList(0,Math.min(topN,rows.size()));
        }
    }

    private List<Map<String,Object>> diversify(List<Map<String,Object>> rows,int limit) {
        return diversify(rows,limit,2);
    }

    private List<Map<String,Object>> diversify(List<Map<String,Object>> rows,int limit,int maxPerSource) {
        List<Map<String,Object>> result=new ArrayList<>(); Map<Object,Integer> sourceCounts=new HashMap<>(); Set<Object> events=new HashSet<>();
        for(Map<String,Object> row:rows){Object source=row.get("source_entity_id");Object event=row.get("event_cluster_id");
            if(sourceCounts.getOrDefault(source,0)>=maxPerSource)continue;if(event!=null&&!events.add(event))continue;
            result.add(row);sourceCounts.merge(source,1,Integer::sum);if(result.size()>=limit)break;}
        return result;
    }

    @SuppressWarnings("unchecked") private Provider provider(UUID actorUserId){
        ProviderConnectionService.RuntimeSelection selection=providerConnections.selection("RAG_GENERATION",actorUserId);
        if(selection.dynamic()){
            return new Provider(selection.provider(),selection.model(),true,selection.connectionId(),
                    selection.scope(),selection.override(),selection.parametersJson());
        }
        try{
            Map<String,Object> response=ai.get().uri("/api/v1/providers").retrieve().body(Map.class);
            Map<String,Object> generation=(Map<String,Object>)response.get("generation");
            String name=String.valueOf(generation.get("provider"));
            return new Provider(name,String.valueOf(generation.get("model")),!isMock(name),
                    selection.connectionId(),selection.scope(),null,selection.parametersJson());
        }catch(Exception ignored){
            return new Provider("extractive-fallback","none",false,null,"FALLBACK",null,"{}");
        }
    }
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

    @SuppressWarnings("unchecked") private GenerationResult generate(String question,RagQueryPlanner.Plan plan,
            List<Map<String,Object>> evidence,PromptTemplate promptTemplate,Provider provider){
        StringBuilder context=new StringBuilder();for(int i=0;i<evidence.size();i++){Map<String,Object> row=evidence.get(i);String text=String.valueOf(row.get("content_text"));context.append('[').append(i+1).append("] ").append(row.get("source_name")).append(" | ").append(row.get("effective_published_at")).append(" | fact_status=").append(row.get("fact_status")).append("\n").append(text,0,Math.min(900,text.length())).append("\n\n");}
        String task=promptTemplate.taskTemplate()
                .replace("{{question}}",question)
                .replace("{{timeRange}}",plan.timeRangeLabel());
        String evidenceMessage=promptTemplate.evidenceTemplate().replace("{{evidence}}",context);
        try{
            int outputBudget=Math.min(1800,1000+evidence.size()*100);
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("system_prompt",promptTemplate.systemTemplate());body.put("user_prompt",task);
            body.put("evidence",evidenceMessage);body.put("max_tokens",outputBudget);
            if(provider.override()!=null)body.put("provider_override",provider.override());
            RestClient.RequestBodySpec request=ai.post().uri("/api/v1/generate").contentType(MediaType.APPLICATION_JSON);
            if(provider.override()!=null)request.header("X-AI-Internal-Token",aiInternalToken);
            Map<String,Object> response=request.body(body).retrieve().body(Map.class);
            GenerationResult parsed=parseGeneration(String.valueOf(response.get("text")),evidence.size());
            return new GenerationResult(parsed.answer(),parsed.assessments(),number(response.get("input_tokens")),
                    number(response.get("output_tokens")),false,null,parsed.structuredOutputValid());
        }catch(Exception error){
            return new GenerationResult(extractiveAnswer(evidence),Map.of(),0,0,true,
                    providerErrorCode(error,"GENERATION"),false);
        }
    }

    @SuppressWarnings("unchecked") private GenerationResult parseGeneration(String raw,int evidenceCount){
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
            boolean assessmentsValid=values instanceof List<?> list&&!list.isEmpty();
            if(values instanceof List<?> list)for(Object value:list){
                if(!(value instanceof Map<?,?> item)){assessmentsValid=false;continue;}
                int citationNo=number(item.get("citationNo"));
                String claim=text(item.get("claimText"),"");
                String stance=text(item.get("stance"),"").toUpperCase(Locale.ROOT);
                String reason=text(item.get("reason"),"");
                if(citationNo<1||citationNo>evidenceCount||claim.isBlank()||reason.isBlank()
                        ||!Set.of("SUPPORTS","REFUTES","UNVERIFIED").contains(stance)){
                    assessmentsValid=false;
                    continue;
                }
                assessments.put(citationNo,new EvidenceAssessmentPolicy.ModelAssessment(
                        citationNo,claim,stance,reason));
            }
            Set<Integer> referenced=citedNumbers(answer);
            boolean structuredValid=!answer.isBlank()&&!referenced.isEmpty()&&assessmentsValid
                    &&assessments.keySet().containsAll(referenced);
            return new GenerationResult(answer,assessments,0,0,false,null,structuredValid);
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

    private void recordGenerationMetric(Provider provider,GenerationResult generated,long latencyMs,
                                        UUID actorUserId,UUID queryRunId){
        BigDecimal cost=estimatedGenerationCost(provider.parametersJson(),generated.inputTokens(),generated.outputTokens());
        recordProviderMetric("RAG_GENERATION",provider.name(),provider.model(),
                generated.failed()?"FAILED":"SUCCEEDED",latencyMs,generated.inputTokens(),
                generated.outputTokens(),generated.errorCode(),cost,provider.connectionId(),
                actorUserId,queryRunId,provider.scope());
    }

    private void recordProviderMetric(String capability,String provider,String model,String status,
                                       long latencyMs,int inputTokens,int outputTokens,String errorCode,
                                       BigDecimal cost,UUID connectionId,UUID actorUserId,
                                       UUID queryRunId,String credentialScope){
        try{
            jdbc.update("""
                insert into knowledge.provider_metric(
                  capability,provider_name,model_name,status,latency_ms,input_tokens,output_tokens,
                  estimated_cost,error_code,connection_id,actor_user_id,query_run_id,credential_scope)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,capability,provider,model,status,latencyMs,inputTokens,outputTokens,cost,errorCode,
                    connectionId,actorUserId,queryRunId,normalizeScope(credentialScope));
        }catch(Exception ignored){}
    }

    @SuppressWarnings("unchecked")
    private BigDecimal estimatedGenerationCost(String configuredParameters,int inputTokens,int outputTokens){
        try{
            String raw=configuredParameters;
            if(raw==null||raw.isBlank()||"{}".equals(raw)){
                raw=jdbc.queryForObject("select parameters::text from knowledge.provider_config where task_type='RAG_GENERATION'",String.class);
            }
            Map<String,Object> parameters=new ObjectMapper().readValue(raw==null?"{}":raw,Map.class);
            double inputRate=decimal(parameters.get("inputCostPerMillion"));
            double outputRate=decimal(parameters.get("outputCostPerMillion"));
            return BigDecimal.valueOf((inputTokens*inputRate+outputTokens*outputRate)/1_000_000d);
        }catch(Exception ignored){return BigDecimal.ZERO;}
    }

    private static String noEvidenceReason(RagQueryPlanner.Plan plan,List<Map<String,Object>> candidates,List<Map<String,Object>> reranked){
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
            jdbc.update("insert into research.citation(id,query_run_id,chunk_id,citation_no,quote_text,retrieval_score,rerank_score,support_status,source_title,source_name,source_url,source_published_at,provenance_status) values(?,?,?,?,?,?,?,'SUPPORTED',?,?,?,?,'VERIFIED')",
                    citationId, runId, row.get("chunk_id"), citationNo,
                    quote.substring(0, Math.min(500, quote.length())), score, score,
                    row.get("title"),row.get("source_name"),row.get("source_url"),row.get("effective_published_at"));
            EvidenceAssessmentPolicy.Assessment assessment=assessments.get(citationNo);
            jdbc.update("insert into research.evidence_assessment(citation_id,claim_text,evidence_stance,freshness_status,assessment_reason,assessment_method,entailment_status,entailment_score) values(?,?,?,?,?,?,?,?)",
                    citationId,assessment.claimText(),assessment.stance(),assessment.freshnessStatus(),assessment.reason(),
                    assessment.method(),assessment.entailmentStatus(),assessment.entailmentScore());
            result.add(new Citation(citationNo, String.valueOf(row.get("title")),
                    String.valueOf(row.get("source_name")), String.valueOf(row.get("source_url")),
                    quote.substring(0, Math.min(360, quote.length())), score,
                    String.valueOf(row.get("effective_published_at")), "SUPPORTED",assessment.stance(),
                    assessment.freshnessStatus(),assessment.claimText(),assessment.reason(),
                    assessment.entailmentStatus(),assessment.entailmentScore(),"VERIFIED"));
        }
        return result;
    }
    private List<Citation> citations(UUID runId) {
        return jdbc.query("""
            select c.citation_no,coalesce(c.source_title,d.title,'历史来源不可追溯') title,
              coalesce(c.source_name,se.name,'历史来源不可追溯') source_name,
              coalesce(c.source_url,ch.source_url,'') source_url,c.quote_text,
              coalesce(c.rerank_score,c.retrieval_score,0) support_score,
              coalesce(c.source_published_at,ch.effective_published_at) effective_published_at,
              c.support_status,coalesce(ea.evidence_stance,'UNVERIFIED') evidence_stance,
              coalesce(ea.freshness_status,'UNKNOWN') freshness_status,
              coalesce(ea.claim_text,'') claim_text,coalesce(ea.assessment_reason,'') assessment_reason,
              coalesce(ea.entailment_status,'NOT_EVALUATED') entailment_status,
              coalesce(ea.entailment_score,0) entailment_score,
              coalesce(c.provenance_status,'LEGACY_MISSING') provenance_status
            from research.citation c
            left join knowledge.chunk ch on ch.id=c.chunk_id
            left join knowledge.document d on d.id=ch.document_id
            left join source.source_entity se on se.id=ch.source_entity_id
            left join research.evidence_assessment ea on ea.citation_id=c.id
            where c.query_run_id=? order by c.citation_no
            """,(rs,row) -> new Citation(rs.getInt("citation_no"),rs.getString("title"),
                    rs.getString("source_name"),rs.getString("source_url"),rs.getString("quote_text"),
                    rs.getDouble("support_score"),String.valueOf(rs.getObject("effective_published_at")),
                    rs.getString("support_status"),rs.getString("evidence_stance"),
                    rs.getString("freshness_status"),rs.getString("claim_text"),
                    rs.getString("assessment_reason"),rs.getString("entailment_status"),
                    rs.getDouble("entailment_score"),rs.getString("provenance_status")),runId);
    }
    private Map<String,Object> diagnostics(RagQueryPlanner.Plan plan,List<Map<String,Object>> candidates,List<Map<String,Object>> reranked,List<Map<String,Object>> evidence,Map<String,Long> stageTimings){Map<String,Object> map=new LinkedHashMap<>();map.put("fusion","RRF+RERANK");map.put("timeRangeDays",plan.days());map.put("timeRangeKind",plan.rangeKind());map.put("timeRangeLabel",plan.timeRangeLabel());map.put("windowStart",plan.windowStart()==null?null:plan.windowStart().toString());map.put("windowEnd",plan.windowEnd()==null?null:plan.windowEnd().toString());map.put("queryIntent",plan.intent());map.put("candidateCount",candidates.size());map.put("rerankedCount",reranked.size());map.put("contextCount",evidence.size());map.put("sourceCount",evidence.stream().map(row->row.get("source_entity_id")).distinct().count());map.put("eventCount",evidence.stream().map(row->row.get("event_cluster_id")).filter(v->v!=null).distinct().count());map.put("officialCount",evidence.stream().filter(row->List.of("OFFICIAL","FIRST_PARTY").contains(String.valueOf(row.get("source_official_level")))).count());map.put("stageTimingsMs",new LinkedHashMap<>(stageTimings));return map;}
    private static long elapsedMillis(long startedNanos){return Math.max(0,(System.nanoTime()-startedNanos)/1_000_000);}
    private static Set<Integer> citedNumbers(String answer){Set<Integer> values=new HashSet<>();Matcher matcher=CITATION.matcher(answer);while(matcher.find())values.add(Integer.parseInt(matcher.group(1)));return values;}
    private static boolean hasOnlyValidCitations(String answer,int evidenceCount){return citedNumbers(answer).stream().allMatch(value->value>=1&&value<=evidenceCount);}
    private static double citationCoverage(String answer){String[] sentences=answer.split("[。！？!?\\n]+");int claims=0,supported=0;for(String sentence:sentences){if(sentence.strip().length()<12)continue;claims++;if(CITATION.matcher(sentence).find())supported++;}return claims==0?0:(double)supported/claims;}
    private static String normalizeGeneratedAnswer(String answer){if(answer==null)return "";return answer.strip().replaceFirst("^(?:\\[(?:C)?\\d+]\\s*[。；;，,]?\\s*)+(?=\\S)","");}
    private static boolean isMock(Object value){return List.of("mock","test","fixture").contains(String.valueOf(value).toLowerCase());}
    private static int number(Object value){if(value instanceof Number number)return number.intValue();try{return Integer.parseInt(String.valueOf(value));}catch(Exception ignored){return 0;}}
    private static double decimal(Object value){if(value instanceof Number number)return number.doubleValue();try{return Double.parseDouble(String.valueOf(value));}catch(Exception ignored){return 0;}}
    private static String text(Object value,String fallback){return value==null?fallback:String.valueOf(value);}
    private static String normalizeScope(String value){
        String normalized=value==null?"ENVIRONMENT":value.toUpperCase(Locale.ROOT);
        return Set.of("PLATFORM","USER","ENVIRONMENT","FALLBACK").contains(normalized)
                ?normalized:"ENVIRONMENT";
    }
    private static String json(Object value){try{return new ObjectMapper().writeValueAsString(value);}catch(Exception ignored){return "{}";}}
    @SuppressWarnings("unchecked")
    private static Map<String,Object> jsonMap(Object value){try{return value==null?Map.of():new ObjectMapper().readValue(String.valueOf(value),Map.class);}catch(Exception ignored){return Map.of();}}
    private record Provider(String name,String model,boolean real,UUID connectionId,String scope,
                            Map<String,Object> override,String parametersJson){}
    private record GenerationResult(String answer,Map<Integer,EvidenceAssessmentPolicy.ModelAssessment> assessments,
                                    int inputTokens,int outputTokens,boolean failed,String errorCode,
                                    boolean structuredOutputValid){}
    private record PromptTemplate(UUID id,String version,String systemTemplate,String taskTemplate,
                                  String evidenceTemplate,String hash){}
    private record RetrievalResult(List<Map<String,Object>> rows,long embeddingMs,long databaseMs){}
    public record Citation(int citationNo,String title,String sourceName,String sourceUrl,String quote,double supportScore,
                           String publishedAt,String supportStatus,String evidenceStance,String freshnessStatus,
                           String claimText,String assessmentReason,String entailmentStatus,double entailmentScore,
                           String provenanceStatus){}
    public record ResearchSessionSummary(UUID id,String title,String status,OffsetDateTime createdAt,
                                         OffsetDateTime updatedAt,String latestQuestion,
                                         String latestAnswerStatus,int queryCount){}
    public record ResearchTurn(UUID runId,String question,String answer,String answerStatus,
                               String generationProvider,List<Citation> citations,long latencyMs,
                               Map<String,Object> diagnostics,OffsetDateTime createdAt,
                               ResearchFeedbackService.Feedback feedback){}
    public record ResearchSessionView(ResearchSessionSummary session,List<ResearchTurn> turns){}
    public record ResearchResult(UUID runId,UUID sessionId,String answer,String answerStatus,
                                 String generationProvider,List<Citation> citations,long latencyMs,
                                 Map<String,Object> diagnostics,int inputTokens,int outputTokens,
                                 BigDecimal estimatedCost,
                                 ResearchFeedbackService.Feedback feedback){}
}
