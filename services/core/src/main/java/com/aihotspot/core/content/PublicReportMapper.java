package com.aihotspot.core.content;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PublicReportMapper {

    List<PublicContentMapper.PublicContentView> listBetween(
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt,
            @Param("limit") int limit);

    ReportMetrics metricsBetween(@Param("startAt") Instant startAt, @Param("endAt") Instant endAt);

    List<ArchiveBucket> archiveBuckets(@Param("period") String period, @Param("limit") int limit);

    LocalDate latestPublishedDate();

    record ReportMetrics(long storyCount, long sourceCount, long officialSourceCount, long featuredCount) {}

    record ArchiveBucket(LocalDate anchorDate, long storyCount, String leadTitle) {}
}
