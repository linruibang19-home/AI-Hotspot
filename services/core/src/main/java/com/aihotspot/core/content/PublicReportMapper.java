package com.aihotspot.core.content;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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

    PersistedIssue findPublishedIssue(@Param("period") String period, @Param("anchor") LocalDate anchor);
    List<ArchiveBucket> publishedArchive(@Param("period") String period, @Param("limit") int limit);
    List<PersistedSection> publishedSections(@Param("issueId") UUID issueId);
    List<PublicContentMapper.PublicContentView> publishedSectionItems(@Param("sectionId") UUID sectionId);

    record ReportMetrics(long storyCount, long eventCount, long sourceCount, long officialSourceCount, long featuredCount) {}

    record ArchiveBucket(LocalDate anchorDate, long storyCount, String leadTitle) {}
    record PersistedIssue(UUID id, String period, String volume, LocalDate startDate, LocalDate endDate,
            String headline, String lead, long storyCount, long eventCount, long sourceCount,
            long officialSourceCount, long featuredCount, int estimatedMinutes) {}
    record PersistedSection(UUID id, String sectionCode, String title, String summary, int sortOrder) {}
}
