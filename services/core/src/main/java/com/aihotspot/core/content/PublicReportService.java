package com.aihotspot.core.content;

import com.aihotspot.core.api.ApiException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PublicReportService {

    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_REPORT_ITEMS = 300;
    private final PublicReportMapper mapper;
    private final Clock clock;

    @Autowired
    public PublicReportService(PublicReportMapper mapper) {
        this(mapper, Clock.system(REPORT_ZONE));
    }

    PublicReportService(PublicReportMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public ReportResponse get(String requestedPeriod, LocalDate requestedAnchor) {
        Period period = parsePeriod(requestedPeriod);
        if (requestedAnchor != null) {
            PublicReportMapper.PersistedIssue published = mapper.findPublishedIssue(period.name(), requestedAnchor);
            if (published != null) return persisted(period, published);
        }
        return generateLive(period, requestedAnchor);
    }

    public ReportResponse generateLive(String requestedPeriod, LocalDate requestedAnchor) {
        return generateLive(parsePeriod(requestedPeriod), requestedAnchor);
    }

    private ReportResponse generateLive(Period period, LocalDate requestedAnchor) {
        LocalDate anchor = requestedAnchor != null
                ? requestedAnchor
                : LocalDate.now(clock);
        DateRange range = range(period, anchor);
        Instant startAt = range.startDate().atStartOfDay(REPORT_ZONE).toInstant();
        Instant endAt = range.endDate().plusDays(1).atStartOfDay(REPORT_ZONE).toInstant();
        List<PublicContentMapper.PublicContentView> items = mapper.listBetween(startAt, endAt, MAX_REPORT_ITEMS);
        PublicReportMapper.ReportMetrics metrics = mapper.metricsBetween(startAt, endAt);
        List<Section> sections = sections(items);
        String headline = headline(period, sections, metrics.storyCount());
        String lead = lead(items, metrics.storyCount());
        List<Highlight> highlights = sections.stream()
                .map(section -> new Highlight(section.code(), section.label(), section.items().size(),
                        section.items().isEmpty() ? null : compactTitle(section.items().get(0).title())))
                .toList();
        ReportView view = new ReportView(
                period.name(), periodLabel(period), "LIVE", clock.instant(), volume(period, range.startDate()),
                range.startDate(), range.endDate(), headline, lead,
                metrics.storyCount(), metrics.eventCount(), metrics.sourceCount(), metrics.officialSourceCount(),
                metrics.featuredCount(), estimatedMinutes(metrics.storyCount()),
                highlights, sections);
        List<ArchiveItem> archive = mapper.archiveBuckets(period.name(), period == Period.DAILY ? 31 : 18)
                .stream()
                .map(row -> new ArchiveItem(row.anchorDate(), row.storyCount(), row.leadTitle()))
                .toList();
        return new ReportResponse(view, archive);
    }

    private ReportResponse persisted(Period period, PublicReportMapper.PersistedIssue issue) {
        List<Section> sections = mapper.publishedSections(issue.id()).stream().map(section -> {
            List<ReportItem> items = mapper.publishedSectionItems(section.id()).stream().map(this::toItem).toList();
            return new Section(section.sectionCode(), section.title(), items);
        }).toList();
        List<Highlight> highlights = sections.stream().map(section -> new Highlight(section.code(), section.label(),
                section.items().size(), section.items().isEmpty() ? null : compactTitle(section.items().get(0).title()))).toList();
        ReportView view = new ReportView(period.name(), periodLabel(period), "PUBLISHED", null,
                issue.volume(), issue.startDate(), issue.endDate(),
                issue.headline(), issue.lead(), issue.storyCount(), issue.eventCount(), issue.sourceCount(),
                issue.officialSourceCount(), issue.featuredCount(), issue.estimatedMinutes(), highlights, sections);
        List<ArchiveItem> archive = mapper.publishedArchive(period.name(), period == Period.DAILY ? 31 : 18).stream()
                .map(row -> new ArchiveItem(row.anchorDate(), row.storyCount(), row.leadTitle())).toList();
        return new ReportResponse(view, archive);
    }

    private Period parsePeriod(String value) {
        try {
            return Period.valueOf(value == null ? "DAILY" : value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REPORT_PERIOD", "报告周期只支持 DAILY、WEEKLY、MONTHLY");
        }
    }

    private DateRange range(Period period, LocalDate anchor) {
        return switch (period) {
            case DAILY -> new DateRange(anchor, anchor);
            case WEEKLY -> {
                LocalDate monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new DateRange(monday, monday.plusDays(6));
            }
            case MONTHLY -> {
                LocalDate first = anchor.withDayOfMonth(1);
                yield new DateRange(first, first.with(TemporalAdjusters.lastDayOfMonth()));
            }
        };
    }

    private List<Section> sections(List<PublicContentMapper.PublicContentView> items) {
        EnumMap<Category, List<ReportItem>> grouped = new EnumMap<>(Category.class);
        for (Category category : Category.values()) grouped.put(category, new ArrayList<>());
        for (PublicContentMapper.PublicContentView item : items) {
            Category category = classify(item);
            if (grouped.get(category).size() < 12) grouped.get(category).add(toItem(item));
        }
        List<Section> result = new ArrayList<>();
        for (Category category : Category.values()) {
            List<ReportItem> rows = grouped.get(category);
            if (!rows.isEmpty()) result.add(new Section(category.name(), category.label, rows));
        }
        return result;
    }

    private Category classify(PublicContentMapper.PublicContentView item) {
        String text = (item.title() + " " + (item.summary() == null ? "" : item.summary())).toLowerCase(Locale.ROOT);
        if (item.contentType().equalsIgnoreCase("RESEARCH") || contains(text,
                "paper", "research", "benchmark", "arxiv", "论文", "研究", "评测", "基准")) return Category.RESEARCH;
        if (contains(text, "release", "model", "llm", "gpt", "claude", "gemini", "qwen", "deepseek",
                "模型", "开源", "权重")) return Category.MODEL;
        if (contains(text, "agent", "coding", "inference", "deploy", "rag", "framework", "github",
                "智能体", "编程", "推理", "部署", "工具", "框架")) return Category.ENGINEERING;
        if (contains(text, "product", "update", "launch", "copilot", "产品", "功能", "上线", "发布")) return Category.PRODUCT;
        return Category.INDUSTRY;
    }

    private boolean contains(String text, String... keywords) {
        for (String keyword : keywords) if (text.contains(keyword)) return true;
        return false;
    }

    private ReportItem toItem(PublicContentMapper.PublicContentView item) {
        return new ReportItem(item.id().toString(), item.title(), item.summary(), item.sourceName(),
                item.sourceOfficialLevel(), item.contentType(), item.finalScore(), item.publishedAt());
    }

    private String headline(Period period, List<Section> sections, long storyCount) {
        if (storyCount == 0) return "本期暂无符合公开准入规则的内容";
        String prefix = period == Period.DAILY ? "今日" : period == Period.WEEKLY ? "本周" : "本月";
        String first = sections.isEmpty() ? "AI 进展" : sections.get(0).label();
        String second = sections.size() < 2 ? "产业动态" : sections.get(1).label();
        return prefix + "主线：" + first + "与" + second;
    }

    private String lead(List<PublicContentMapper.PublicContentView> items, long storyCount) {
        if (items.isEmpty()) return "当前周期没有可公开展示的内容；可从左侧归档切换到已有报告。";
        StringBuilder builder = new StringBuilder("本期共收录 ").append(storyCount)
                .append(" 条通过准入的公开内容，重点包括：");
        for (int index = 0; index < Math.min(items.size(), 3); index++) {
            if (index > 0) builder.append("；");
            builder.append(compactTitle(items.get(index).title()));
        }
        return builder.append("。本内容由规则生成草稿，管理员可在报告编辑台校订并发布正式版本。").toString();
    }

    private String periodLabel(Period period) {
        return switch (period) {
            case DAILY -> "日报";
            case WEEKLY -> "周报";
            case MONTHLY -> "月报";
        };
    }

    private String volume(Period period, LocalDate startDate) {
        return switch (period) {
            case DAILY -> "VOL." + startDate.toString().replace('-', '.');
            case WEEKLY -> "VOL." + startDate.getYear() + "-W" + String.format("%02d", startDate.get(WeekFields.ISO.weekOfWeekBasedYear()));
            case MONTHLY -> "VOL." + startDate.getYear() + "-" + String.format("%02d", startDate.getMonthValue());
        };
    }

    private int estimatedMinutes(long stories) {
        return stories == 0 ? 0 : Math.max(2, (int) Math.ceil(Math.min(stories, 60) * 0.45));
    }

    private String compactTitle(String title) {
        String normalized = title == null ? "" : title.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 93) + "…";
    }

    private enum Period { DAILY, WEEKLY, MONTHLY }
    private enum Category {
        MODEL("模型发布/更新"), PRODUCT("产品发布/更新"), ENGINEERING("工程与智能体"),
        RESEARCH("论文研究与评测"), INDUSTRY("行业动态与观点");
        private final String label;
        Category(String label) { this.label = label; }
    }

    private record DateRange(LocalDate startDate, LocalDate endDate) {}
    public record ReportResponse(ReportView report, List<ArchiveItem> archive) {}
    public record ReportView(
            String period, String periodLabel, String source, Instant generatedAt,
            String volume, LocalDate startDate, LocalDate endDate,
            String headline, String lead, long storyCount, long eventCount, long sourceCount, long officialSourceCount,
            long featuredCount, int estimatedMinutes, List<Highlight> highlights, List<Section> sections) {}
    public record ArchiveItem(LocalDate anchorDate, long storyCount, String leadTitle) {}
    public record Highlight(String code, String label, int displayedCount, String leadTitle) {}
    public record Section(String code, String label, List<ReportItem> items) {}
    public record ReportItem(
            String id, String title, String summary, String sourceName, String sourceOfficialLevel,
            String contentType, java.math.BigDecimal finalScore, Instant publishedAt) {}
}
