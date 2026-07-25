package com.aihotspot.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RagQueryPlannerTests {
    private static final ZonedDateTime SUNDAY =
            ZonedDateTime.of(2026, 7, 26, 3, 0, 0, 0, ZoneId.of("Asia/Shanghai"));

    @Test
    void usesShanghaiCalendarWeekForWeeklyReport() {
        RagQueryPlanner.Plan plan = RagQueryPlanner.plan("本周周报", Map.of(), SUNDAY);

        assertThat(plan.intent()).isEqualTo("WEEKLY_REPORT");
        assertThat(plan.rangeKind()).isEqualTo("THIS_WEEK");
        assertThat(plan.windowStart().toString()).isEqualTo("2026-07-20T00:00+08:00");
        assertThat(plan.windowEnd().toString()).isEqualTo("2026-07-26T03:00+08:00");
        assertThat(plan.timeRangeLabel()).isEqualTo("2026-07-20 至 2026-07-26（Asia/Shanghai，本周截至当前）");
        assertThat(plan.semanticQuery()).contains("产品发布", "工程研究");
    }

    @Test
    void keepsRollingWindowForExplicitRecentDays() {
        RagQueryPlanner.Plan plan = RagQueryPlanner.plan("最近7天 Agent 产品变化", Map.of(), SUNDAY);

        assertThat(plan.rangeKind()).isEqualTo("ROLLING_7D");
        assertThat(plan.windowStart().toString()).isEqualTo("2026-07-19T03:00+08:00");
        assertThat(plan.windowEnd().toString()).isEqualTo("2026-07-26T03:00+08:00");
        assertThat(plan.intent()).isEqualTo("RESEARCH");
    }

    @Test
    void respectsManualAllTimeWhenQuestionHasNoTimeIntent() {
        RagQueryPlanner.Plan plan = RagQueryPlanner.plan(
                "什么是混合召回",
                Map.of("timeRange", "all", "officialOnly", true),
                SUNDAY);

        assertThat(plan.windowStart()).isNull();
        assertThat(plan.windowEnd()).isNull();
        assertThat(plan.timeRangeLabel()).isEqualTo("不限时间");
        assertThat(plan.officialOnly()).isTrue();
    }
}
