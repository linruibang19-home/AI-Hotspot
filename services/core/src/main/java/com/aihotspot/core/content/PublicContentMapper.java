package com.aihotspot.core.content;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PublicContentMapper {

    List<PublicContentView> list(
            @Param("featured") boolean featured,
            @Param("cursorPublishedAt") Instant cursorPublishedAt,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit);

    PublicContentView findPublicById(@Param("id") UUID id);

    record PublicContentView(
            UUID id,
            String sourceName,
            String sourceType,
            String sourceOfficialLevel,
            String contentType,
            String title,
            String originalTitle,
            String summary,
            String recommendationReason,
            String originalUrl,
            String canonicalUrl,
            String factStatus,
            java.math.BigDecimal finalScore,
            boolean featured,
            Instant sourcePublishedAt,
            Instant publishedAt) {}
}

