package com.aihotspot.core.knowledge;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class EvidenceAssessmentPolicy {
    private static final Set<String> STANCES = Set.of("SUPPORTS", "REFUTES", "UNVERIFIED");
    private static final Pattern RECENCY = Pattern.compile("最近|近期|当前|目前|最新|现在|今天|本周|本月|过去\\s*\\d+\\s*天", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUESTIONED_CLAIM = Pattern.compile("属实|传闻|消息.{0,8}(?:真假|真的)|是否.{0,12}(?:发布|推出|宣布|发生|取消|停止|脱离|关闭|泄露)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_DENIAL = Pattern.compile("辟谣|否认|不实|虚假|并未|从未|没有.{0,8}(?:计划|发生|推出|发布)|not true|denied|refut", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFLICT_DISCLOSURE = Pattern.compile("冲突|反驳|否认|不一致|相反");
    private static final Pattern UNVERIFIED_DISCLOSURE = Pattern.compile("未证实|尚待核验|无法证实|有待确认");
    private static final Pattern OUTDATED_DISCLOSURE = Pattern.compile("过期|较早|时效|不再适用|已被更新");

    private EvidenceAssessmentPolicy() {}

    static Map<Integer,Assessment> assess(
            List<Map<String,Object>> evidence,
            Map<Integer,ModelAssessment> modelAssessments,
            String question,
            int timeRangeDays) {
        Map<Integer,Assessment> result = new LinkedHashMap<>();
        boolean recencySensitive = timeRangeDays > 0 || RECENCY.matcher(question == null ? "" : question).find();
        int freshnessDays = timeRangeDays > 0 ? Math.max(7, timeRangeDays) : 90;
        Instant freshnessCutoff = Instant.now().minus(freshnessDays, ChronoUnit.DAYS);
        for (int index = 0; index < evidence.size(); index++) {
            int citationNo = index + 1;
            Map<String,Object> row = evidence.get(index);
            ModelAssessment model = modelAssessments.get(citationNo);
            String factStatus = String.valueOf(row.getOrDefault("fact_status", "CONFIRMED"));
            String stance = normalizeStance(model == null ? null : model.stance());
            String method = model == null ? "DEFAULT_RULES" : "MODEL_AND_RULES";
            String reason = model == null ? "该证据与回答中的相关陈述一致。" : safe(model.reason(), 500);
            String ruleClaim = null;
            if ("UNCONFIRMED".equals(factStatus)) {
                stance = "UNVERIFIED";
                method = "RULES";
                reason = "内容治理状态为未证实，不能作为已确认事实表达。";
            } else if (QUESTIONED_CLAIM.matcher(question == null ? "" : question).find()
                    && EXPLICIT_DENIAL.matcher(String.valueOf(row.getOrDefault("content_text", ""))).find()) {
                stance = "REFUTES";
                method = "RULES";
                reason = "证据明确否认或辟谣用户询问的原始命题。";
                ruleClaim = positiveClaim(question);
            }
            Instant publishedAt = publishedInstant(row.get("effective_published_at"));
            String freshness = publishedAt == null ? "UNKNOWN"
                    : recencySensitive && publishedAt.isBefore(freshnessCutoff) ? "OUTDATED" : "CURRENT";
            if ("OUTDATED".equals(freshness)) {
                method = "RULES";
                reason = "该证据早于当前问题的时效窗口，可能已被后续信息取代。";
            }
            String fallbackClaim = String.valueOf(row.getOrDefault("title", "相关事实"));
            String claim = safe(ruleClaim != null ? ruleClaim : model == null ? fallbackClaim : model.claimText(), 300);
            if (claim.isBlank()) claim = safe(fallbackClaim, 300);
            result.put(citationNo, new Assessment(citationNo, claim, stance, freshness, reason, method));
        }
        return result;
    }

    static DisclosureResult disclose(String answer, Map<Integer,Assessment> assessments) {
        String result = answer == null ? "" : answer.strip();
        ConflictPair conflict = conflictPair(assessments);
        if (conflict != null && !CONFLICT_DISCLOSURE.matcher(result).find()) {
            result = "证据存在冲突：关于“" + conflict.support().claimText() + "”，有来源提供支持 ["
                    + conflict.support().citationNo() + "]，也有来源提出反驳或否认 ["
                    + conflict.refute().citationNo() + "]。\n\n" + result;
        }
        Assessment unverified = assessments.values().stream()
                .filter(value -> "UNVERIFIED".equals(value.stance())).findFirst().orElse(null);
        if (unverified != null && !UNVERIFIED_DISCLOSURE.matcher(result).find()) {
            result += "\n\n其中部分信息仍未被独立证实，应作为待核验说法理解 [" + unverified.citationNo() + "]。";
        }
        Assessment outdated = assessments.values().stream()
                .filter(value -> "OUTDATED".equals(value.freshnessStatus())).findFirst().orElse(null);
        if (outdated != null && !OUTDATED_DISCLOSURE.matcher(result).find()) {
            result += "\n\n较早证据可能已过期，不宜直接代表当前状态，请以更新来源为准 [" + outdated.citationNo() + "]。";
        }
        return new DisclosureResult(result, conflict != null);
    }

    static Map<String,Long> stanceCounts(Map<Integer,Assessment> assessments) {
        Map<String,Long> counts = new LinkedHashMap<>();
        for (String stance : List.of("SUPPORTS", "REFUTES", "UNVERIFIED")) {
            counts.put(stance, assessments.values().stream().filter(value -> stance.equals(value.stance())).count());
        }
        counts.put("OUTDATED", assessments.values().stream().filter(value -> "OUTDATED".equals(value.freshnessStatus())).count());
        return counts;
    }

    private static ConflictPair conflictPair(Map<Integer,Assessment> assessments) {
        Map<String,List<Assessment>> byClaim = new LinkedHashMap<>();
        for (Assessment assessment : assessments.values()) {
            String key = assessment.claimText().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s，。；：！？、]+", "");
            if (!key.isBlank()) byClaim.computeIfAbsent(key, ignored -> new ArrayList<>()).add(assessment);
        }
        for (List<Assessment> group : byClaim.values()) {
            Assessment support = group.stream().filter(value -> "SUPPORTS".equals(value.stance())).findFirst().orElse(null);
            Assessment refute = group.stream().filter(value -> "REFUTES".equals(value.stance())).findFirst().orElse(null);
            if (support != null && refute != null) return new ConflictPair(support, refute);
        }
        return null;
    }

    private static String normalizeStance(String value) {
        String normalized = value == null ? "SUPPORTS" : value.strip().toUpperCase(Locale.ROOT);
        return STANCES.contains(normalized) ? normalized : "SUPPORTS";
    }

    private static String safe(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.substring(0, Math.min(maxLength, normalized.length()));
    }

    private static String positiveClaim(String question) {
        if (question == null) return "用户询问的原始命题";
        String claim = question.replaceAll("[？?。！!]+$", "")
                .replaceAll("(?:的)?消息是否属实.*$", "")
                .replace("是否", "")
                .replaceAll("[？?]", "").strip();
        return claim.isBlank() ? "用户询问的原始命题" : claim;
    }

    private static Instant publishedInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime time) return time.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        String text = String.valueOf(value);
        try { return OffsetDateTime.parse(text).toInstant(); }
        catch (Exception ignored) {
            try { return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC); }
            catch (Exception invalid) { return null; }
        }
    }

    record ModelAssessment(int citationNo, String claimText, String stance, String reason) {}
    record Assessment(int citationNo, String claimText, String stance, String freshnessStatus,
                      String reason, String method) {}
    record DisclosureResult(String answer, boolean conflictDetected) {}
    private record ConflictPair(Assessment support, Assessment refute) {}
}
