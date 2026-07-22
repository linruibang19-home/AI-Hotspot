package com.aihotspot.core.source;

import com.aihotspot.core.audit.AuditService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SourceQualityService {

    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public SourceQualityService(JdbcTemplate jdbc, AuditService audit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> currentReport() {
        Map<String, Object> summary = jdbc.queryForMap("""
            select count(*) endpoint_count,
                   count(*) filter (where assessment='HEALTHY') healthy_count,
                   count(*) filter (where assessment='WATCH') watch_count,
                   count(*) filter (where assessment='UNDERPERFORMING') underperforming_count,
                   count(*) filter (where assessment='INSUFFICIENT_DATA') insufficient_data_count,
                   coalesce(sum(item_count),0) item_count,
                   coalesce(sum(published_count),0) published_count,
                   coalesce(sum(duplicate_count),0) duplicate_count,
                   coalesce(sum(missing_source_time_count),0) missing_source_time_count,
                   coalesce(round(sum(duplicate_count)::numeric/nullif(sum(item_count),0),5),0) duplicate_rate,
                   coalesce(round(1-sum(missing_source_time_count)::numeric/nullif(sum(item_count),0),5),0) source_time_completeness,
                   coalesce(round(max(published_source_share),5),0) largest_source_share
            from source.source_quality_current
            """);
        List<Map<String, Object>> byOfficialLevel = jdbc.queryForList("""
            select official_level, count(*) endpoint_count, sum(item_count) item_count,
                   sum(published_count) published_count,
                   round(avg(quality_score) filter (where quality_score is not null),3) avg_quality_score,
                   round(sum(duplicate_count)::numeric/nullif(sum(item_count),0),5) duplicate_rate,
                   round(1-sum(missing_source_time_count)::numeric/nullif(sum(item_count),0),5) source_time_completeness
            from source.source_quality_current group by official_level order by official_level
            """);
        List<Map<String, Object>> endpoints = jdbc.queryForList("""
            select endpoint_id, source_entity_id, source_name, endpoint_name, endpoint_type,
                   catalog_kind, endpoint_status, health_status, official_level, authority_score,
                   quality_weight, quality_state, item_count, published_count, duplicate_count,
                   missing_source_time_count, avg_relevance_score, avg_quality_score, avg_final_score,
                   round(publication_rate,5) publication_rate, round(duplicate_rate,5) duplicate_rate,
                   round(source_time_completeness,5) source_time_completeness,
                   round(published_source_share,5) published_source_share,
                   quality_score, assessment
            from source.source_quality_current
            order by case assessment when 'UNDERPERFORMING' then 0 when 'WATCH' then 1
                     when 'INSUFFICIENT_DATA' then 2 else 3 end,
                     quality_score asc nulls last, item_count desc, source_name
            """);
        return Map.of("windowDays", 7, "generatedAt", java.time.Instant.now(),
                "summary", summary, "byOfficialLevel", byOfficialLevel, "endpoints", endpoints);
    }

    public List<Map<String, Object>> history(int weeks) {
        return jdbc.queryForList("""
            select q.week_start, q.endpoint_id, s.name source_name, e.name endpoint_name,
                   q.official_level, q.item_count, q.published_count, q.duplicate_count,
                   q.missing_source_time_count, q.avg_quality_score, q.publication_rate,
                   q.duplicate_rate, q.source_time_completeness, q.published_source_share,
                   q.quality_score, q.assessment, q.decision_reason, q.action_applied
            from source.source_quality_weekly_snapshot q
            join source.source_entity s on s.id=q.source_entity_id
            join source.source_endpoint e on e.id=q.endpoint_id
            where q.week_start >= current_date - (? * interval '7 days')
            order by q.week_start desc, q.quality_score asc nulls last, s.name
            """, Math.min(Math.max(weeks, 1), 52));
    }

    @Transactional
    public RunResult captureWeeklySnapshot() {
        LocalDate weekStart = LocalDate.now(REPORT_ZONE)
                .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        int snapshots = jdbc.update("""
            insert into source.source_quality_weekly_snapshot(
                id,endpoint_id,source_entity_id,week_start,official_level,health_status,
                item_count,published_count,duplicate_count,missing_source_time_count,
                avg_relevance_score,avg_quality_score,avg_final_score,publication_rate,
                duplicate_rate,source_time_completeness,published_source_share,quality_score,
                assessment,decision_reason)
            select gen_random_uuid(),endpoint_id,source_entity_id,?,official_level,health_status,
                   item_count,published_count,duplicate_count,missing_source_time_count,
                   avg_relevance_score,avg_quality_score,avg_final_score,publication_rate,
                   duplicate_rate,source_time_completeness,published_source_share,quality_score,
                   assessment,
                   case assessment
                     when 'INSUFFICIENT_DATA' then '最近7天有效样本少于20条，仅观察，不触发自动治理'
                     when 'HEALTHY' then '综合质量不低于70且发布时间完整率不低于80%'
                     when 'WATCH' then '质量或发布时间完整率进入观察区间'
                     else '综合质量低于50或发布时间完整率低于50%'
                   end
            from source.source_quality_current
            on conflict(endpoint_id,week_start) do update set
                official_level=excluded.official_level,health_status=excluded.health_status,
                item_count=excluded.item_count,published_count=excluded.published_count,
                duplicate_count=excluded.duplicate_count,
                missing_source_time_count=excluded.missing_source_time_count,
                avg_relevance_score=excluded.avg_relevance_score,
                avg_quality_score=excluded.avg_quality_score,avg_final_score=excluded.avg_final_score,
                publication_rate=excluded.publication_rate,duplicate_rate=excluded.duplicate_rate,
                source_time_completeness=excluded.source_time_completeness,
                published_source_share=excluded.published_source_share,
                quality_score=excluded.quality_score,assessment=excluded.assessment,
                decision_reason=excluded.decision_reason,updated_at=now()
            """, weekStart);

        List<Map<String, Object>> current = jdbc.queryForList("""
            select endpoint_id,official_level,assessment,quality_score,publication_rate,
                   source_time_completeness from source.source_quality_weekly_snapshot
            where week_start=?
            """, weekStart);
        int downranked = 0;
        int paused = 0;
        for (Map<String, Object> row : current) {
            UUID endpointId = (UUID) row.get("endpoint_id");
            String assessment = String.valueOf(row.get("assessment"));
            int streak = underperformingStreak(endpointId);
            QualityAction action = decideAction(String.valueOf(row.get("official_level")), assessment,
                    streak, number(row.get("quality_score")), number(row.get("publication_rate")));
            if (apply(endpointId, action, assessment, streak, weekStart)) {
                if (action == QualityAction.DOWNRANK) downranked++;
                if (action == QualityAction.PAUSE) paused++;
            }
        }
        return new RunResult(weekStart, snapshots, downranked, paused);
    }

    static QualityAction decideAction(String officialLevel, String assessment, int underperformingStreak,
                                      Double qualityScore, Double publicationRate) {
        if (!"THIRD_PARTY".equals(officialLevel) || !"UNDERPERFORMING".equals(assessment)) {
            return "HEALTHY".equals(assessment) ? QualityAction.RESTORE : QualityAction.OBSERVE;
        }
        if (underperformingStreak >= 3 && qualityScore != null && qualityScore < 35
                && publicationRate != null && publicationRate < 0.10) return QualityAction.PAUSE;
        if (underperformingStreak >= 2) return QualityAction.DOWNRANK;
        return QualityAction.OBSERVE;
    }

    private int underperformingStreak(UUID endpointId) {
        List<String> recent = jdbc.queryForList("""
            select assessment from source.source_quality_weekly_snapshot
            where endpoint_id=? order by week_start desc limit 3
            """, String.class, endpointId);
        int streak = 0;
        for (String value : recent) {
            if (!"UNDERPERFORMING".equals(value)) break;
            streak++;
        }
        return streak;
    }

    private boolean apply(UUID endpointId, QualityAction action, String assessment, int streak, LocalDate weekStart) {
        String reason = "week=" + weekStart + ", assessment=" + assessment + ", underperformingStreak=" + streak;
        int changed = switch (action) {
            case PAUSE -> jdbc.update("""
                update source.source_endpoint set status='PAUSED',next_fetch_at=null,
                    quality_weight=0.500,quality_state='AUTO_PAUSED',quality_reason=?,
                    quality_evaluated_at=now(),version=version+1,updated_at=now()
                where id=? and status='ACTIVE' and quality_state<>'AUTO_PAUSED'
                """, reason, endpointId);
            case DOWNRANK -> jdbc.update("""
                update source.source_endpoint set quality_weight=least(quality_weight,0.750),
                    quality_state='DOWNRANKED',quality_reason=?,quality_evaluated_at=now(),
                    version=version+1,updated_at=now()
                where id=? and quality_state not in ('DOWNRANKED','AUTO_PAUSED')
                """, reason, endpointId);
            case RESTORE -> jdbc.update("""
                update source.source_endpoint set quality_weight=1.000,quality_state='HEALTHY',
                    quality_reason=?,quality_evaluated_at=now(),version=version+1,updated_at=now()
                where id=? and quality_state not in ('HEALTHY','AUTO_PAUSED')
                """, reason, endpointId);
            case OBSERVE -> jdbc.update("""
                update source.source_endpoint set quality_state=case when ? in ('WATCH','UNDERPERFORMING') then 'WATCH' else quality_state end,
                    quality_reason=?,quality_evaluated_at=now(),updated_at=now()
                where id=? and quality_state not in ('DOWNRANKED','AUTO_PAUSED')
                """, assessment, reason, endpointId);
        };
        if (changed > 0 && (action == QualityAction.DOWNRANK || action == QualityAction.PAUSE)) {
            jdbc.update("update source.source_quality_weekly_snapshot set action_applied=? where endpoint_id=? and week_start=?",
                    action == QualityAction.PAUSE ? "PAUSED" : "DOWNRANKED", endpointId, weekStart);
            audit.record(null, "SOURCE_QUALITY_" + action.name(), "SOURCE_ENDPOINT", endpointId,
                    null, json(Map.of("reason", reason)), null);
        }
        return changed > 0;
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ignored) { return "{}"; }
    }

    enum QualityAction { OBSERVE, RESTORE, DOWNRANK, PAUSE }
    public record RunResult(LocalDate weekStart, int snapshots, int downranked, int paused) {}
}
