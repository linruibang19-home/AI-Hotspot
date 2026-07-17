package com.aihotspot.core.content;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContentGovernanceMapper {
    ContentMetrics metrics();
    List<ContentReviewItem> list(@Param("status") String status, @Param("factStatus") String factStatus,
                                 @Param("query") String query, @Param("limit") int limit);
    ContentReviewItem findById(@Param("id") UUID id);
    int govern(@Param("id") UUID id, @Param("publicationStatus") String publicationStatus,
               @Param("visibility") String visibility, @Param("admissionStatus") String admissionStatus,
               @Param("factStatus") String factStatus, @Param("featured") boolean featured,
               @Param("version") long version);
    List<EventView> listEvents(@Param("limit") int limit);
    EventView findEventById(@Param("id") UUID id);
    int addRelation(@Param("contentId") UUID contentId, @Param("eventId") UUID eventId,
                    @Param("relationType") String relationType, @Param("confidence") BigDecimal confidence,
                    @Param("createdBy") UUID createdBy);
    int removeRelation(@Param("contentId") UUID contentId, @Param("eventId") UUID eventId);
    void insertTicket(TicketEntry entry);
    List<TicketView> listTickets(@Param("status") String status, @Param("limit") int limit);
    TicketView findTicketById(@Param("id") UUID id);
    int resolveTicket(@Param("id") UUID id, @Param("status") String status,
                      @Param("resolution") String resolution, @Param("resolvedBy") UUID resolvedBy);

    record ContentMetrics(long reviewRequired, long unconfirmed, long duplicates, long openTickets) {}
    record ContentReviewItem(UUID id, String title, String sourceName, String originalUrl,
            String categoryCode, String publicationStatus, String visibility, String admissionStatus,
            String factStatus, BigDecimal relevanceScore, BigDecimal qualityScore, BigDecimal finalScore,
            boolean featured, boolean duplicate, UUID duplicateOfId, String qualityDimensions,
            String tags, String events, long version, Instant updatedAt) {}
    record EventView(UUID id, String title, String summary, String categoryCode, String status,
            String factStatus, long contentCount, Instant firstSeenAt, Instant lastSeenAt, long version) {}
    record TicketEntry(UUID id, UUID contentItemId, UUID eventClusterId, String ticketType,
            String priority, String reason, String contactEmail, UUID submittedBy) {}
    record TicketView(UUID id, UUID contentItemId, UUID eventClusterId, String ticketType,
            String status, String priority, String reason, String contactEmail, String resolution,
            UUID submittedBy, UUID assignedTo, UUID resolvedBy, Instant createdAt, Instant resolvedAt) {}
}
