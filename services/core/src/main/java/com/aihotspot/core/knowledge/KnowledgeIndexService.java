package com.aihotspot.core.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Service
public class KnowledgeIndexService {
    private final JdbcTemplate jdbc;
    private final RestClient ai;
    private final TransactionTemplate transactions;
    public KnowledgeIndexService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            @Value("${ai-hotspot.ai-base-url}") String aiBaseUrl) {
        this.jdbc = jdbc;
        this.ai = RestClient.builder().baseUrl(aiBaseUrl).requestFactory(new SimpleClientHttpRequestFactory()).build();
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${ai-hotspot.knowledge.index-fixed-delay:60000}", initialDelayString = "${ai-hotspot.knowledge.index-initial-delay:30000}")
    public void indexPublicContent() { indexBatch(100); }

    public synchronized IndexResult indexBatch(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        Map<String,Object> dataset = jdbc.queryForMap("select id,chunk_size,chunk_overlap from knowledge.dataset where code='PUBLIC_AI_CONTENT' and status='ACTIVE'");
        UUID datasetId = (UUID) dataset.get("id");
        int chunkSize = ((Number) dataset.get("chunk_size")).intValue();
        int chunkOverlap = ((Number) dataset.get("chunk_overlap")).intValue();
        jdbc.update("""
            update knowledge.document d set status='REMOVED',updated_at=now(),last_error='SOURCE_NOT_PUBLIC'
            from content.content_item c
            where d.content_item_id=c.id and d.status='INDEXED'
              and not(c.index_policy='PUBLIC_RAG' and c.admission_status='PASSED'
                and c.publication_status='PUBLISHED' and c.visibility='PUBLIC'
                and c.provider_name is not null and lower(c.provider_name) not in ('mock','test','fixture'))
            """);
        jdbc.update("""
            update knowledge.document d set status='FAILED',updated_at=now(),last_error='MISSING_EMBEDDING'
            where d.status='INDEXED' and exists (
              select 1 from knowledge.chunk ch where ch.document_id=d.id and ch.embedding is null
            )
            """);
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select c.id, coalesce(c.title_zh,c.original_title) title,
                   c.summary_zh generated_summary, r.raw_summary source_body, c.index_policy,
                   coalesce(c.canonical_url,c.original_url) source_url, c.source_published_at,
                   c.effective_published_at, c.source_entity_id, c.source_official_level,
                   s.authority_score, c.quality_score, c.final_score, ev.event_cluster_id
            from content.content_item c
            join source.raw_entry r on r.id=c.raw_entry_id
            join source.source_entity s on s.id=c.source_entity_id
            left join lateral (
              select cer.event_cluster_id from content.content_event_relation cer
              where cer.content_item_id=c.id order by cer.is_primary desc,cer.created_at limit 1
            ) ev on true
            where c.index_policy='PUBLIC_RAG' and c.admission_status='PASSED'
              and c.publication_status='PUBLISHED' and c.visibility='PUBLIC'
              and c.provider_name is not null and lower(c.provider_name) not in ('mock','test','fixture')
              and not exists (select 1 from knowledge.document d where d.content_item_id=c.id and d.status='INDEXED')
            order by c.effective_published_at desc, c.id limit ?
            """, limit);
        int indexed = 0;
        for (Map<String,Object> row : rows) {
            UUID contentId = (UUID) row.get("id");
            String title = String.valueOf(row.get("title"));
            String sourceBody = cleanText(row.get("source_body"));
            String generatedSummary = cleanText(row.get("generated_summary"));
            String body = sourceBody.length() >= 120 ? sourceBody : generatedSummary;
            if (body.isBlank()) body = title;
            String hash = sha256(title + "\n" + body);
            String evidenceText = "标题：" + title + "\n\n原文证据：\n" + body.substring(0, Math.min(body.length(), 20000));
            List<String> chunks = chunk(evidenceText, chunkSize, chunkOverlap);
            EmbeddingBatch embeddingBatch = embeddings(chunks);
            boolean complete = embeddingBatch.vectors.size() == chunks.size()
                    && embeddingBatch.vectors.stream().allMatch(vector -> vector.size() == 1024);
            if (!complete) {
                transactions.executeWithoutResult(status -> upsertFailedDocument(datasetId, contentId, title,
                        String.valueOf(row.get("index_policy")), hash));
                continue;
            }
            transactions.executeWithoutResult(status -> storeDocument(datasetId, row, contentId, title, hash, chunks, embeddingBatch));
            indexed++;
        }
        return new IndexResult(indexed, rows.size());
    }

    private void upsertFailedDocument(UUID datasetId, UUID contentId, String title, String indexPolicy, String hash) {
        jdbc.update("""
            insert into knowledge.document(id,dataset_id,content_item_id,title,index_policy,status,content_hash,indexed_at,last_error)
            values(?,?,?,?,?,'FAILED',?,null,'EMBEDDING_INCOMPLETE')
            on conflict(dataset_id,content_item_id) do update set title=excluded.title,index_policy=excluded.index_policy,
              status='FAILED',content_hash=excluded.content_hash,indexed_at=null,last_error='EMBEDDING_INCOMPLETE',updated_at=now()
            """, UUID.randomUUID(), datasetId, contentId, title, indexPolicy, hash);
    }

    private void storeDocument(UUID datasetId, Map<String,Object> row, UUID contentId, String title,
            String hash, List<String> chunks, EmbeddingBatch embeddingBatch) {
        jdbc.update("""
            insert into knowledge.document(id,dataset_id,content_item_id,title,index_policy,status,content_hash,indexed_at)
            values(?,?,?,?,?,'PENDING',?,null)
            on conflict(dataset_id,content_item_id) do update set title=excluded.title,index_policy=excluded.index_policy,
              status='PENDING',content_hash=excluded.content_hash,indexed_at=null,last_error=null,updated_at=now()
            """, UUID.randomUUID(), datasetId, contentId, title, row.get("index_policy"), hash);
        UUID documentId = jdbc.queryForObject("select id from knowledge.document where dataset_id=? and content_item_id=?", UUID.class, datasetId, contentId);
        jdbc.update("delete from knowledge.chunk where document_id=?", documentId);
        for (int index = 0; index < chunks.size(); index++) {
            String text = chunks.get(index);
            UUID chunkId = UUID.randomUUID();
            jdbc.update("""
                insert into knowledge.chunk(id,document_id,ordinal,content_text,token_count,source_url,source_published_at,
                  content_kind,source_entity_id,event_cluster_id,source_official_level,authority_score,quality_score,final_score,effective_published_at)
                values(?,?,?,?,?,?,?,'SOURCE_BODY',?,?,?,?,?,?,?)
                """, chunkId, documentId, index, text, Math.max(1, text.length() / 3), row.get("source_url"), row.get("source_published_at"),
                row.get("source_entity_id"),row.get("event_cluster_id"),row.get("source_official_level"),row.get("authority_score"),
                row.get("quality_score"),row.get("final_score"),row.get("effective_published_at"));
            jdbc.update("update knowledge.chunk set embedding=?::vector,embedding_model=?,embedded_at=now() where id=?",
                    vectorLiteral(embeddingBatch.vectors.get(index)), embeddingBatch.model, chunkId);
        }
        jdbc.update("update knowledge.document set status='INDEXED',indexed_at=now(),last_error=null,updated_at=now() where id=?", documentId);
    }

    private static List<String> chunk(String value, int size, int overlap) {
        if (value.length() <= size) return List.of(value);
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(value.length(), start + size);
            result.add(value.substring(start, end));
            if (end == value.length()) break;
            start = Math.max(start + 1, end - overlap);
        }
        return result;
    }
    private static String cleanText(Object value) {
        if (value == null) return "";
        return String.valueOf(value)
                .replaceAll("(?is)<script.*?</script>|<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("\\s+", " ").strip();
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    @SuppressWarnings("unchecked")
    private EmbeddingBatch embeddings(List<String> texts) {
        try {
            Map<String,Object> providers = ai.get().uri("/api/v1/providers").retrieve().body(Map.class);
            Map<String,Object> embedding = (Map<String,Object>) providers.get("embedding");
            String provider = String.valueOf(embedding.get("provider"));
            if (List.of("mock","test","fixture").contains(provider.toLowerCase())) return new EmbeddingBatch("", List.of());
            Map<String,Object> response = ai.post().uri("/api/v1/embed").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("texts", texts)).retrieve().body(Map.class);
            String model = String.valueOf(response.get("model"));
            List<List<Double>> vectors = new java.util.ArrayList<>();
            for (Object row : (List<Object>) response.getOrDefault("vectors", List.of())) {
                List<Double> vector = new java.util.ArrayList<>();
                for (Object value : (List<Object>) row) vector.add(((Number) value).doubleValue());
                vectors.add(vector);
            }
            return new EmbeddingBatch(model, vectors);
        } catch (Exception error) {
            return new EmbeddingBatch("", List.of());
        }
    }
    private static String vectorLiteral(List<Double> vector) {
        return vector.stream().map(String::valueOf).reduce("[", (left,right) -> left.equals("[") ? left + right : left + "," + right) + "]";
    }
    private record EmbeddingBatch(String model, List<List<Double>> vectors) {}
    public record IndexResult(int indexedDocuments, int discoveredDocuments) {}
}
