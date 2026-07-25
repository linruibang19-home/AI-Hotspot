package com.aihotspot.core.knowledge;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RagQueryPlanner {
    static final ZoneId PRODUCT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern DAYS = Pattern.compile("(?:最近|近|过去)\\s*(\\d{1,3})\\s*天");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String REPORT_EXPANSION = "人工智能 大模型 Agent 产品发布 工程研究 行业动态";

    private RagQueryPlanner() {
    }

    static Plan plan(String query, Map<String, Object> filters) {
        return plan(query, filters, ZonedDateTime.now(PRODUCT_ZONE));
    }

    static Plan plan(String query, Map<String, Object> filters, ZonedDateTime now) {
        String safeQuery = query == null ? "" : query.strip();
        Map<String, Object> safeFilters = filters == null ? Map.of() : filters;
        String selectedRange = String.valueOf(safeFilters.getOrDefault("timeRange", "auto"));
        ZonedDateTime start = null;
        String rangeKind = "ALL";
        int days = 0;

        Matcher matcher = DAYS.matcher(safeQuery);
        if (matcher.find()) {
            days = Math.min(365, Integer.parseInt(matcher.group(1)));
            start = now.minusDays(days);
            rangeKind = "ROLLING_" + days + "D";
        } else if (safeQuery.contains("今天")) {
            days = 1;
            start = now.toLocalDate().atStartOfDay(PRODUCT_ZONE);
            rangeKind = "TODAY";
        } else if (safeQuery.contains("本周")) {
            start = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate().atStartOfDay(PRODUCT_ZONE);
            days = (int) java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), now.toLocalDate()) + 1;
            rangeKind = "THIS_WEEK";
        } else if (safeQuery.contains("本月")) {
            start = now.withDayOfMonth(1).toLocalDate().atStartOfDay(PRODUCT_ZONE);
            days = (int) java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), now.toLocalDate()) + 1;
            rangeKind = "THIS_MONTH";
        } else if (safeQuery.contains("近期")
                || safeQuery.matches(".*(?:最近|近来)(?!\\s*\\d{1,3}\\s*天).*")) {
            days = 30;
            start = now.minusDays(days);
            rangeKind = "ROLLING_30D";
        } else if (!"all".equals(selectedRange) && !"auto".equals(selectedRange)) {
            days = switch (selectedRange) {
                case "7d" -> 7;
                case "30d" -> 30;
                case "90d" -> 90;
                default -> 0;
            };
            if (days > 0) {
                start = now.minusDays(days);
                rangeKind = "ROLLING_" + days + "D";
            }
        }

        ZonedDateTime end = start == null ? null : now;
        boolean weeklyReport = safeQuery.contains("周报")
                || safeQuery.matches(".*本周(?:总结|动态|进展|要闻).*");
        String intent = weeklyReport ? "WEEKLY_REPORT" : "RESEARCH";
        String lexical = safeQuery
                .replaceAll("(?:最近|近|过去)\\s*\\d{1,3}\\s*天|今天|本周|本月|近期|近来|有哪些|是什么|请|总结|分析|周报", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (lexical.length() < 2) lexical = weeklyReport ? "AI" : safeQuery;
        String semantic = weeklyReport ? safeQuery + "；" + REPORT_EXPANSION : safeQuery;
        String rangeLabel = rangeLabel(start, end, rangeKind);

        return new Plan(
                lexical,
                semantic,
                start == null ? null : start.toOffsetDateTime(),
                end == null ? null : end.toOffsetDateTime(),
                days,
                rangeKind,
                rangeLabel,
                intent,
                Boolean.parseBoolean(String.valueOf(safeFilters.getOrDefault("officialOnly", false))),
                blankToNull(safeFilters.get("sourceType")));
    }

    private static String rangeLabel(ZonedDateTime start, ZonedDateTime end, String kind) {
        if (start == null || end == null) return "不限时间";
        String suffix = switch (kind) {
            case "TODAY" -> "今天截至当前";
            case "THIS_WEEK" -> "本周截至当前";
            case "THIS_MONTH" -> "本月截至当前";
            default -> "滚动时间窗";
        };
        return DATE.format(start) + " 至 " + DATE.format(end) + "（Asia/Shanghai，" + suffix + "）";
    }

    private static String blankToNull(Object value) {
        String text = value == null ? "" : String.valueOf(value).strip();
        return text.isBlank() ? null : text;
    }

    record Plan(
            String lexicalQuery,
            String semanticQuery,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            int days,
            String rangeKind,
            String timeRangeLabel,
            String intent,
            boolean officialOnly,
            String sourceType) {
        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("lexicalQuery", lexicalQuery);
            map.put("semanticQuery", semanticQuery);
            map.put("timeRangeDays", days);
            map.put("timeRangeKind", rangeKind);
            map.put("timeRangeLabel", timeRangeLabel);
            map.put("windowStart", windowStart == null ? null : windowStart.toString());
            map.put("windowEnd", windowEnd == null ? null : windowEnd.toString());
            map.put("officialOnly", officialOnly);
            map.put("sourceType", sourceType);
            map.put("intent", intent);
            return map;
        }
    }
}
