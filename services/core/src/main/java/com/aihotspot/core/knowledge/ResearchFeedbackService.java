package com.aihotspot.core.knowledge;

import com.aihotspot.core.api.ApiException;
import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResearchFeedbackService {
    static final Set<String> RATINGS = Set.of("HELPFUL", "UNHELPFUL");
    static final Set<String> CATEGORIES = Set.of(
            "IRRELEVANT", "MISSING_EVIDENCE", "OUTDATED", "INCORRECT",
            "INCOMPLETE", "CITATION_MISMATCH", "TOO_VERBOSE", "OTHER");
    static final Set<String> TRIAGE_STATUSES = Set.of(
            "TRIAGED", "EVAL_CANDIDATE", "RESOLVED", "DISMISSED");

    private final JdbcTemplate jdbc;

    public ResearchFeedbackService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Feedback submit(UUID userId, UUID runId, String ratingValue,
                           List<String> categoryValues, String commentValue) {
        String rating;
        List<String> categories;
        String comment;
        try {
            rating = normalizeRequired(ratingValue, "rating");
            if (!RATINGS.contains(rating)) {
                throw new IllegalArgumentException("rating 必须为 HELPFUL 或 UNHELPFUL");
            }
            categories = normalizeCategories(categoryValues);
            if ("UNHELPFUL".equals(rating) && categories.isEmpty()) {
                throw new IllegalArgumentException("不满意反馈至少选择一个原因");
            }
            comment = normalizeComment(commentValue);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_RESEARCH_FEEDBACK",
                    exception.getMessage());
        }
        if ("HELPFUL".equals(rating)) {
            categories = List.of();
        }
        Integer owned = jdbc.queryForObject("""
            select count(*) from research.query_run
            where id=? and user_id=? and answer_status in ('SUCCEEDED','NO_EVIDENCE')
            """, Integer.class, runId, userId);
        if (owned == null || owned == 0) {
            // Do not reveal whether another user's research run exists.
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "RESEARCH_ANSWER_NOT_FOUND",
                    "研究回答不存在、尚未完成或不可访问");
        }
        UUID feedbackId = UUID.randomUUID();
        jdbc.update("""
            insert into research.answer_feedback(
              id,query_run_id,session_id,user_id,rating,issue_categories,comment
            )
            select ?,qr.id,qr.session_id,qr.user_id,?,?::text[],?
            from research.query_run qr where qr.id=? and qr.user_id=?
            on conflict(query_run_id) do update set
              rating=excluded.rating,
              issue_categories=excluded.issue_categories,
              comment=excluded.comment,
              triage_status='NEW',
              triaged_by=null,
              triaged_at=null,
              updated_at=now()
            """, feedbackId, rating, pgArray(categories), comment, runId, userId);
        return findForRun(userId, runId);
    }

    public Feedback findForRun(UUID userId, UUID runId) {
        List<Feedback> rows = jdbc.query("""
            select id,query_run_id,rating,issue_categories,comment,triage_status,created_at,updated_at
            from research.answer_feedback where query_run_id=? and user_id=?
            """, (rs, row) -> new Feedback(
                rs.getObject("id", UUID.class),
                rs.getObject("query_run_id", UUID.class),
                rs.getString("rating"),
                sqlArray(rs.getArray("issue_categories")),
                rs.getString("comment"),
                rs.getString("triage_status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)), runId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> summary(int hours) {
        return jdbc.queryForMap("""
            select count(*) total,
              count(*) filter(where rating='HELPFUL') helpful,
              count(*) filter(where rating='UNHELPFUL') unhelpful,
              count(*) filter(where triage_status='NEW') awaiting_triage,
              count(*) filter(where triage_status='EVAL_CANDIDATE') eval_candidates,
              coalesce(round(100.0*count(*) filter(where rating='HELPFUL')/nullif(count(*),0),1),0)
                helpful_rate
            from research.answer_feedback
            where created_at>=now()-(? * interval '1 hour')
            """, hours);
    }

    public List<Map<String, Object>> samples(int hours, int limit) {
        return jdbc.query("""
            select f.id,f.query_run_id,f.rating,f.issue_categories,f.comment,f.triage_status,
              f.created_at,f.updated_at,q.question,q.answer_status,q.latency_ms,q.citation_coverage,
              coalesce((q.retrieval_diagnostics->>'sourceCount')::int,0) source_count,
              coalesce(q.retrieval_diagnostics->>'promptVersion','') prompt_version
            from research.answer_feedback f
            join research.query_run q on q.id=f.query_run_id
            where f.created_at>=now()-(? * interval '1 hour')
            order by (f.triage_status='NEW') desc,(f.rating='UNHELPFUL') desc,f.updated_at desc
            limit ?
            """, (rs, row) -> {
                Map<String, Object> sample = new java.util.LinkedHashMap<>();
                sample.put("id", rs.getObject("id", UUID.class));
                sample.put("query_run_id", rs.getObject("query_run_id", UUID.class));
                sample.put("rating", rs.getString("rating"));
                sample.put("issue_categories", sqlArray(rs.getArray("issue_categories")));
                sample.put("comment", rs.getString("comment"));
                sample.put("triage_status", rs.getString("triage_status"));
                sample.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
                sample.put("updated_at", rs.getObject("updated_at", OffsetDateTime.class));
                sample.put("question", rs.getString("question"));
                sample.put("answer_status", rs.getString("answer_status"));
                sample.put("latency_ms", rs.getLong("latency_ms"));
                sample.put("citation_coverage", rs.getBigDecimal("citation_coverage"));
                sample.put("source_count", rs.getInt("source_count"));
                sample.put("prompt_version", rs.getString("prompt_version"));
                return sample;
            }, hours, Math.max(1, Math.min(limit, 100)));
    }

    @Transactional
    public Feedback triage(UUID adminId, UUID feedbackId, String statusValue) {
        String status;
        try {
            status = normalizeRequired(statusValue, "status");
            if (!TRIAGE_STATUSES.contains(status)) {
                throw new IllegalArgumentException(
                        "status 必须为 TRIAGED、EVAL_CANDIDATE、RESOLVED 或 DISMISSED");
            }
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_FEEDBACK_TRIAGE",
                    exception.getMessage());
        }
        int updated = jdbc.update("""
            update research.answer_feedback set triage_status=?,triaged_by=?,triaged_at=now(),updated_at=now()
            where id=?
            """, status, adminId, feedbackId);
        if (updated == 0) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "RESEARCH_FEEDBACK_NOT_FOUND",
                    "反馈不存在");
        }
        return jdbc.query("""
            select id,query_run_id,rating,issue_categories,comment,triage_status,created_at,updated_at
            from research.answer_feedback where id=?
            """, (rs, row) -> new Feedback(
                rs.getObject("id", UUID.class),
                rs.getObject("query_run_id", UUID.class),
                rs.getString("rating"),
                sqlArray(rs.getArray("issue_categories")),
                rs.getString("comment"),
                rs.getString("triage_status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)), feedbackId).get(0);
    }

    static List<String> normalizeCategories(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String category = normalizeRequired(value, "category");
            if (!CATEGORIES.contains(category)) {
                throw new IllegalArgumentException("未知反馈原因：" + category);
            }
            normalized.add(category);
        }
        return List.copyOf(normalized);
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }

    private static String normalizeComment(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > 1000) {
            throw new IllegalArgumentException("补充说明不能超过 1000 个字符");
        }
        return normalized.isBlank() ? null : normalized;
    }

    private static String pgArray(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    private static List<String> sqlArray(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    public record Feedback(
            UUID id, UUID runId, String rating, List<String> issueCategories,
            String comment, String triageStatus, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}
