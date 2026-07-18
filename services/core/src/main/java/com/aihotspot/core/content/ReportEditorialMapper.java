package com.aihotspot.core.content;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReportEditorialMapper {
    String upsertIssue(@Param("id") UUID id, @Param("period") String period, @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate, @Param("volume") String volume, @Param("headline") String headline,
            @Param("lead") String lead, @Param("storyCount") long storyCount, @Param("eventCount") long eventCount,
            @Param("sourceCount") long sourceCount, @Param("officialSourceCount") long officialSourceCount,
            @Param("featuredCount") long featuredCount, @Param("estimatedMinutes") int estimatedMinutes);
    int deleteSections(@Param("issueId") UUID issueId);
    void insertSection(@Param("id") UUID id, @Param("issueId") UUID issueId, @Param("code") String code,
            @Param("title") String title, @Param("sortOrder") int sortOrder);
    void insertItem(@Param("id") UUID id, @Param("sectionId") UUID sectionId,
            @Param("contentId") UUID contentId, @Param("position") int position);
    List<IssueView> list(@Param("limit") int limit);
    IssueView find(@Param("id") UUID id);
    int update(@Param("id") UUID id, @Param("headline") String headline, @Param("lead") String lead,
            @Param("version") long version);
    int publish(@Param("id") UUID id, @Param("actorId") UUID actorId, @Param("version") long version);

    record IssueView(UUID id, String period, LocalDate startDate, LocalDate endDate, String volume,
            String headline, String lead, String status, long storyCount, long eventCount, long sourceCount,
            long officialSourceCount, long featuredCount, int estimatedMinutes, long version,
            Instant generatedAt, Instant publishedAt, UUID publishedBy, Instant updatedAt) {}
}
