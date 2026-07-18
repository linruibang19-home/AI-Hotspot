package com.aihotspot.core.content;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PublicDiscoveryMapper {
    List<PublicContentMapper.PublicContentView> search(@Param("query") String query,
            @Param("sourceType") String sourceType, @Param("contentType") String contentType,
            @Param("limit") int limit);
    List<TopicView> listTopics();
    TopicView findTopic(@Param("slug") String slug);
    List<PublicContentMapper.PublicContentView> listTopicContents(@Param("queryText") String queryText,
            @Param("limit") int limit);
    List<EventView> listEvents(@Param("limit") int limit);
    EventView findEvent(@Param("id") UUID id);
    List<PublicContentMapper.PublicContentView> listEventContents(@Param("id") UUID id,
            @Param("limit") int limit);

    record TopicView(UUID id, String slug, String groupCode, String name, String description,
            String queryText, long contentCount, long featuredCount, Instant updatedAt) {}
    record EventView(UUID id, String title, String summary, String categoryCode, String factStatus,
            long contentCount, long sourceCount, BigDecimal heatScore, Instant firstSeenAt,
            Instant lastSeenAt) {}
}
