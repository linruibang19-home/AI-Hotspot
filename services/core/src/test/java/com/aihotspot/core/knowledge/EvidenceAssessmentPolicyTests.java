package com.aihotspot.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceAssessmentPolicyTests {
    @Test
    void governanceAndFreshnessRulesOverrideModelConfidence() {
        var evidence = List.of(
                row("尚未确认的发布", "UNCONFIRMED", 1),
                row("旧版本说明", "CONFIRMED", 180));
        var model = Map.of(
                1, new EvidenceAssessmentPolicy.ModelAssessment(1, "产品已经发布", "SUPPORTS", "来源声称已发布"),
                2, new EvidenceAssessmentPolicy.ModelAssessment(2, "当前仍支持旧接口", "SUPPORTS", "旧文档如此描述"));

        var assessed = EvidenceAssessmentPolicy.assess(evidence, model, "目前产品状态如何", 0);

        assertThat(assessed.get(1).stance()).isEqualTo("UNVERIFIED");
        assertThat(assessed.get(1).method()).isEqualTo("RULES");
        assertThat(assessed.get(2).freshnessStatus()).isEqualTo("OUTDATED");
    }

    @Test
    void disclosureNamesConflictUnverifiedAndOutdatedEvidenceWithCitations() {
        var assessments = Map.of(
                1, new EvidenceAssessmentPolicy.Assessment(1, "模型已经公开发布", "SUPPORTS", "CURRENT", "支持", "MODEL_AND_RULES"),
                2, new EvidenceAssessmentPolicy.Assessment(2, "模型已经公开发布", "REFUTES", "CURRENT", "否认", "MODEL_AND_RULES"),
                3, new EvidenceAssessmentPolicy.Assessment(3, "发布日期", "UNVERIFIED", "CURRENT", "待核验", "RULES"),
                4, new EvidenceAssessmentPolicy.Assessment(4, "旧定价", "SUPPORTS", "OUTDATED", "已过期", "RULES"));

        var result = EvidenceAssessmentPolicy.disclose("当前资料给出了不同说法。", assessments);

        assertThat(result.conflictDetected()).isTrue();
        assertThat(result.answer()).contains("证据存在冲突", "[1]", "[2]", "未被独立证实", "[3]", "可能已过期", "[4]");
    }

    @Test
    void unrelatedSupportAndRefuteClaimsDoNotCreateFalseConflict() {
        var assessments = Map.of(
                1, new EvidenceAssessmentPolicy.Assessment(1, "产品定价", "SUPPORTS", "CURRENT", "支持", "MODEL_AND_RULES"),
                2, new EvidenceAssessmentPolicy.Assessment(2, "发布日期", "REFUTES", "CURRENT", "否认", "MODEL_AND_RULES"));

        var result = EvidenceAssessmentPolicy.disclose("两条独立信息。", assessments);

        assertThat(result.conflictDetected()).isFalse();
        assertThat(result.answer()).doesNotContain("证据存在冲突");
    }

    @Test
    void explicitDenialRefutesTheUsersOriginalProposition() {
        var evidence = List.of(Map.<String,Object>of(
                "title", "官方回应",
                "content_text", "品牌发布公告辟谣，明确表示并未脱离现有合作体系。",
                "fact_status", "CONFIRMED",
                "effective_published_at", java.sql.Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant())));
        var model = Map.of(1, new EvidenceAssessmentPolicy.ModelAssessment(
                1, "品牌脱离合作体系的消息不实", "SUPPORTS", "官方已辟谣"));

        var assessed = EvidenceAssessmentPolicy.assess(evidence, model, "品牌脱离合作体系的消息是否属实？", 0);

        assertThat(assessed.get(1).stance()).isEqualTo("REFUTES");
        assertThat(assessed.get(1).method()).isEqualTo("RULES");
        assertThat(assessed.get(1).claimText()).isEqualTo("品牌脱离合作体系");
    }

    private Map<String,Object> row(String title, String factStatus, int ageDays) {
        return Map.of(
                "title", title,
                "fact_status", factStatus,
                "effective_published_at", java.sql.Timestamp.from(
                        OffsetDateTime.now(ZoneOffset.UTC).minusDays(ageDays).toInstant()));
    }
}
