package com.aihotspot.core.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aihotspot.core.api.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicReportServiceTests {

    @Test
    void dailyReportUsesShanghaiDayAndRealMetrics() {
        PublicReportMapper mapper = mapperWithDefaults();
        when(mapper.listBetween(any(), any(), eq(300))).thenReturn(List.of(content(
                "Qwen 模型发布", "模型发布摘要", "OFFICIAL", "NEWS", Instant.parse("2026-07-24T08:00:00Z"))));
        when(mapper.metricsBetween(any(), any()))
                .thenReturn(new PublicReportMapper.ReportMetrics(8, 6, 5, 4, 2));

        var response = serviceAt(mapper, "2026-07-24T12:00:00Z").get("daily", null);

        assertThat(response.report().startDate()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(response.report().endDate()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(response.report().source()).isEqualTo("LIVE");
        assertThat(response.report().generatedAt()).isEqualTo(Instant.parse("2026-07-24T12:00:00Z"));
        assertThat(response.report().storyCount()).isEqualTo(8);
        assertThat(response.report().sourceCount()).isEqualTo(5);
        assertThat(response.report().sections()).extracting(PublicReportService.Section::code)
                .containsExactly("MODEL");
        verify(mapper).listBetween(
                Instant.parse("2026-07-23T16:00:00Z"), Instant.parse("2026-07-24T16:00:00Z"), 300);
    }

    @Test
    void currentReportDoesNotGetStuckOnLatestPublishedEditorialIssue() {
        PublicReportMapper mapper = mapperWithDefaults();

        var response = serviceAt(mapper, "2026-07-24T12:00:00Z").get("WEEKLY", null);

        assertThat(response.report().startDate()).isEqualTo(LocalDate.of(2026, 7, 20));
        verify(mapper, org.mockito.Mockito.never()).findPublishedIssue(any(), any());
    }

    @Test
    void anchoredReportPreservesPublishedEditorialVersion() {
        PublicReportMapper mapper = mapperWithDefaults();
        LocalDate anchor = LocalDate.of(2026, 7, 18);
        UUID issueId = UUID.randomUUID();
        when(mapper.findPublishedIssue("DAILY", anchor)).thenReturn(new PublicReportMapper.PersistedIssue(
                issueId, "DAILY", "VOL.2026.07.18", anchor, anchor, "编辑标题", "编辑导语",
                8, 6, 5, 4, 2, 4));
        when(mapper.publishedSections(issueId)).thenReturn(List.of());
        when(mapper.publishedArchive("DAILY", 31)).thenReturn(List.of());

        var response = serviceAt(mapper, "2026-07-24T12:00:00Z").get("DAILY", anchor);

        assertThat(response.report().source()).isEqualTo("PUBLISHED");
        assertThat(response.report().headline()).isEqualTo("编辑标题");
        assertThat(response.report().generatedAt()).isNull();
    }

    @Test
    void weeklyAndMonthlyReportsNormalizeTheirDateRanges() {
        PublicReportMapper weeklyMapper = mapperWithDefaults();
        new PublicReportService(weeklyMapper).get("WEEKLY", LocalDate.of(2026, 7, 17));
        verify(weeklyMapper).listBetween(
                Instant.parse("2026-07-12T16:00:00Z"), Instant.parse("2026-07-19T16:00:00Z"), 300);

        PublicReportMapper monthlyMapper = mapperWithDefaults();
        var monthly = new PublicReportService(monthlyMapper).get("MONTHLY", LocalDate.of(2026, 7, 17));
        assertThat(monthly.report().startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(monthly.report().endDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        verify(monthlyMapper).listBetween(
                Instant.parse("2026-06-30T16:00:00Z"), Instant.parse("2026-07-31T16:00:00Z"), 300);
    }

    @Test
    void rejectsUnsupportedPeriodBeforeQueryingContent() {
        PublicReportMapper mapper = mock(PublicReportMapper.class);

        assertThatThrownBy(() -> new PublicReportService(mapper).get("YEARLY", null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_REPORT_PERIOD"));
    }

    private PublicReportMapper mapperWithDefaults() {
        PublicReportMapper mapper = mock(PublicReportMapper.class);
        when(mapper.listBetween(any(), any(), eq(300))).thenReturn(List.of());
        when(mapper.metricsBetween(any(), any()))
                .thenReturn(new PublicReportMapper.ReportMetrics(0, 0, 0, 0, 0));
        when(mapper.archiveBuckets(any(), any(Integer.class))).thenReturn(List.of());
        return mapper;
    }

    private PublicReportService serviceAt(PublicReportMapper mapper, String instant) {
        return new PublicReportService(
                mapper,
                Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Shanghai")));
    }

    private PublicContentMapper.PublicContentView content(
            String title, String summary, String officialLevel, String contentType, Instant publishedAt) {
        return new PublicContentMapper.PublicContentView(
                UUID.randomUUID(), "Source", "OFFICIAL", officialLevel, contentType, title, title, summary,
                "推荐理由", "https://example.com/item", "https://example.com/item", "VERIFIED",
                BigDecimal.valueOf(88), true, publishedAt, publishedAt);
    }
}
