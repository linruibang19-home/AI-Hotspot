package com.aihotspot.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RagEvaluationScorerTests {
    @Test
    void scoresRelevantOfficialAndDiverseEvidence() {
        var expected = Map.<String,Object>of(
                "mustContainAny", List.of("Agent", "智能体"),
                "minimumDistinctSources", 2,
                "officialOnly", true,
                "publicOnly", true);

        var score = RagEvaluationScorer.score(expected, List.of(
                row("AI Agent release", "OFFICIAL", UUID.randomUUID(), 1, "PUBLIC"),
                row("智能体工程实践", "FIRST_PARTY", UUID.randomUUID(), 2, "PUBLIC")));

        assertThat(score.passed()).isTrue();
        assertThat(score.firstRelevantRank()).isEqualTo(1);
        assertThat(score.distinctSources()).isEqualTo(2);
        assertThat(score.ndcgGain()).isEqualTo(1.0);
    }

    @Test
    void reportsScopeAndTimeViolations() {
        var expected = Map.<String,Object>of(
                "mustContainAny", List.of("OpenAI"),
                "officialOnly", true,
                "publicOnly", true,
                "maxAgeDays", 7);

        var score = RagEvaluationScorer.score(expected, List.of(
                row("OpenAI report", "THIRD_PARTY", UUID.randomUUID(), 30, "PRIVATE")));

        assertThat(score.passed()).isFalse();
        assertThat(score.violations()).containsExactlyInAnyOrder(
                "non-official-result", "acl-scope-violation", "outside-time-window");
        assertThat(score.ndcgGain()).isZero();
    }

    @Test
    void treatsEmptyScopedRetrievalAsExpectedNoEvidence() {
        var score = RagEvaluationScorer.score(Map.of("expectNoEvidence", true), List.of());

        assertThat(score.passed()).isTrue();
        assertThat(score.ndcgGain()).isEqualTo(1.0);
    }

    @Test
    void failsNoEvidenceCaseWhenCandidatesLeakIntoScope() {
        var score = RagEvaluationScorer.score(Map.of("expectNoEvidence", true), List.of(
                row("Unexpected result", "OFFICIAL", UUID.randomUUID(), 1, "PUBLIC")));

        assertThat(score.passed()).isFalse();
        assertThat(score.violations()).containsExactly("expected-no-evidence");
    }

    @Test
    void requiresAllCrossResultTermsWhenConfigured() {
        var score = RagEvaluationScorer.score(
                Map.of("mustContainAny", List.of("RAG"), "mustContainAll", List.of("RAG", "retrieval")),
                List.of(row("RAG overview", "OFFICIAL", UUID.randomUUID(), 1, "PUBLIC")));

        assertThat(score.passed()).isFalse();
        assertThat(score.violations()).containsExactly("missing-term:retrieval");
    }

    private Map<String,Object> row(String text, String officialLevel, UUID sourceId, int ageDays,
                                   String datasetVisibility) {
        return Map.ofEntries(
                Map.entry("title", text),
                Map.entry("content_text", text),
                Map.entry("source_official_level", officialLevel),
                Map.entry("source_entity_id", sourceId),
                Map.entry("dataset_visibility", datasetVisibility),
                Map.entry("content_visibility", "PUBLIC"),
                Map.entry("publication_status", "PUBLISHED"),
                Map.entry("effective_published_at", java.sql.Timestamp.from(
                        OffsetDateTime.now(ZoneOffset.UTC).minusDays(ageDays).toInstant())));
    }
}
