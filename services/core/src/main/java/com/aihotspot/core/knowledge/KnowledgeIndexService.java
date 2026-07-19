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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Service
public class KnowledgeIndexService {
    private final JdbcTemplate jdbc;
    private final RestClient ai;
    public KnowledgeIndexService(JdbcTemplate jdbc, @Value("${ai-hotspot.ai-base-url}") String aiBaseUrl) {
        this.jdbc = jdbc;
        this.ai = RestClient.create(aiBaseUrl);
    }

    @Scheduled(fixedDelayString = "${ai-hotspot.knowledge.index-fixed-delay:60000}", initialDelayString = "${ai-hotspot.knowledge.index-initial-delay:30000}")
    @Transactional
    public void indexPublicContent() { indexBatch(100); }

    @Transactional
    public IndexResult indexBatch(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        UUID datasetId = jdbc.queryForObject("select id from knowledge.dataset where code='PUBLIC_AI_CONTENT' and status='ACTIVE'", UUID.class);
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select c.id, coalesce(c.title_zh,c.original_title) title,
                   coalesce(c.summary_zh,c.original_title) body, c.index_policy,
                   coalesce(c.canonical_url,c.original_url) source_url, c.source_published_at
            from content.content_item c
            where c.index_policy='PUBLIC_RAG' and c.admission_status='PASSED'
              and c.provider_name is not null and lower(c.provider_name) not in ('mock','test','fixture')
              and not exists (select 1 from knowledge.document d where d.content_item_id=c.id and d.status='INDEXED')
            order by c.processed_at nulls last, c.id limit ?
            """, limit);
        int indexed = 0;
        for (Map<String,Object> row : rows) {
            UUID contentId = (UUID) row.get("id");
            String title = String.valueOf(row.get("title"));
            String body = String.valueOf(row.get("body"));
            String hash = sha256(title + "\n" + body);
            UUID documentId = UUID.randomUUID();
            jdbc.update("""
                insert into knowledge.document(id,dataset_id,content_item_id,title,index_policy,status,content_hash,indexed_at)
                values(?,?,?,?,?,'INDEXED',?,now())
                on conflict(dataset_id,content_item_id) do update set title=excluded.title,index_policy=excluded.index_policy,
                  status='INDEXED',content_hash=excluded.content_hash,indexed_at=now(),last_error=null,updated_at=now()
                """, documentId, datasetId, contentId, title, row.get("index_policy"), hash);
            UUID actualDocumentId = jdbc.queryForObject("select id from knowledge.document where dataset_id=? and content_item_id=?", UUID.class, datasetId, contentId);
            jdbc.update("delete from knowledge.chunk where document_id=?", actualDocumentId);
            List<String> chunks = chunk(title + "\n" + body, 900, 120);
            EmbeddingBatch embeddingBatch = embeddings(chunks);
            for (int index = 0; index < chunks.size(); index++) {
                String text = chunks.get(index);
                UUID chunkId = UUID.randomUUID();
                jdbc.update("""
                    insert into knowledge.chunk(id,document_id,ordinal,content_text,token_count,source_url,source_published_at)
                    values(?,?,?,?,?,?,?)
                    """, chunkId, actualDocumentId, index, text, Math.max(1, text.length() / 3), row.get("source_url"), row.get("source_published_at"));
                if (embeddingBatch.vectors.size() > index) {
                    List<Double> vector = embeddingBatch.vectors.get(index);
                    if (vector.size() == 1024) {
                        jdbc.update("update knowledge.chunk set embedding=?::vector,embedding_model=?,embedding_status='READY',updated_at=now() where id=?",
                                vectorLiteral(vector), embeddingBatch.model, chunkId);
                    }
                }
            }
            indexed++;
        }
        return new IndexResult(indexed, rows.size());
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
