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
import java.util.Objects;

final class RagEvaluationScorer {
    private RagEvaluationScorer() {}

    static CaseScore score(Map<String,Object> expected, List<Map<String,Object>> rows) {
        boolean expectNoEvidence = booleanValue(expected.get("expectNoEvidence"));
        List<String> anyTerms = strings(expected.get("mustContainAny"));
        List<String> allTerms = strings(expected.get("mustContainAll"));
        String corpus = rows.stream()
                .map(RagEvaluationScorer::searchableText)
                .reduce("", (left, right) -> left + "\n" + right);

        int firstRelevant = 0;
        if (!expectNoEvidence) {
            for (int index = 0; index < rows.size(); index++) {
                String text = searchableText(rows.get(index));
                if (anyTerms.isEmpty() || anyTerms.stream().anyMatch(text::contains)) {
                    firstRelevant = index + 1;
                    break;
                }
            }
        }

        List<String> violations = new ArrayList<>();
        if (expectNoEvidence) {
            if (!rows.isEmpty()) violations.add("expected-no-evidence");
        } else if (firstRelevant == 0) {
            violations.add("missing-relevant-result");
        }
        for (String term : allTerms) {
            if (!corpus.contains(term.toLowerCase(Locale.ROOT))) violations.add("missing-term:" + term);
        }

        int minimumSources = intValue(expected.get("minimumDistinctSources"), 0);
        long distinctSources = rows.stream().map(row -> row.get("source_entity_id")).filter(Objects::nonNull).distinct().count();
        if (distinctSources < minimumSources) violations.add("insufficient-source-diversity");

        if (booleanValue(expected.get("officialOnly")) && rows.stream().anyMatch(row ->
                !List.of("OFFICIAL", "FIRST_PARTY").contains(String.valueOf(row.get("source_official_level"))))) {
            violations.add("non-official-result");
        }
        if (booleanValue(expected.get("publicOnly")) && rows.stream().anyMatch(row ->
                !"PUBLIC".equals(String.valueOf(row.get("dataset_visibility")))
                        || !"PUBLIC".equals(String.valueOf(row.get("content_visibility")))
                        || !"PUBLISHED".equals(String.valueOf(row.get("publication_status"))))) {
            violations.add("acl-scope-violation");
        }

        int maxAgeDays = intValue(expected.get("maxAgeDays"), 0);
        if (maxAgeDays > 0) {
            Instant cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(maxAgeDays, ChronoUnit.DAYS).toInstant();
            boolean outsideWindow = rows.stream().anyMatch(row -> {
                Object value = row.get("effective_published_at");
                if (value == null) return true;
                return publishedInstant(value).isBefore(cutoff);
            });
            if (outsideWindow) violations.add("outside-time-window");
        }

        boolean passed = violations.isEmpty();
        double gain = !passed ? 0.0 : expectNoEvidence ? 1.0
                : 1.0 / (Math.log(firstRelevant + 1) / Math.log(2));
        return new CaseScore(passed, firstRelevant, gain, distinctSources, List.copyOf(violations));
    }

    private static String searchableText(Map<String,Object> row) {
        return (String.valueOf(row.get("title")) + " " + String.valueOf(row.get("content_text"))).toLowerCase(Locale.ROOT);
    }

    private static Instant publishedInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime time) return time.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        String text = String.valueOf(value);
        try { return OffsetDateTime.parse(text).toInstant(); }
        catch (Exception ignored) { return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC); }
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).filter(text -> !text.isBlank())
                .map(text -> text.toLowerCase(Locale.ROOT)).toList();
    }

    record CaseScore(boolean passed, int firstRelevantRank, double ndcgGain, long distinctSources,
                     List<String> violations) {
        Map<String,Object> asMap(String caseKey, int candidates) {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("caseKey", caseKey);
            result.put("hit", passed);
            result.put("firstRelevantRank", firstRelevantRank);
            result.put("candidates", candidates);
            result.put("distinctSources", distinctSources);
            result.put("violations", violations);
            return result;
        }
    }
}
