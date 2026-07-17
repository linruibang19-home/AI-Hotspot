package com.aihotspot.core.content;

import com.aihotspot.core.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PublicContentService {

    private final PublicContentMapper mapper;

    public PublicContentService(PublicContentMapper mapper) {
        this.mapper = mapper;
    }

    public Page list(boolean featured, String cursor, int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), 50);
        Cursor decoded = decode(cursor);
        List<PublicContentMapper.PublicContentView> rows = mapper.list(
                featured,
                decoded == null ? null : decoded.publishedAt(),
                decoded == null ? null : decoded.id(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<PublicContentMapper.PublicContentView> items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? encode(items.get(items.size() - 1)) : null;
        return new Page(items, nextCursor, hasMore);
    }

    public PublicContentMapper.PublicContentView detail(UUID id) {
        PublicContentMapper.PublicContentView content = mapper.findPublicById(id);
        if (content == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PUBLIC_CONTENT_NOT_FOUND", "公开内容不存在或不可访问");
        }
        return content;
    }

    private Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) throw new IllegalArgumentException("cursor parts");
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CURSOR", "分页游标不正确");
        }
    }

    private String encode(PublicContentMapper.PublicContentView item) {
        String value = item.publishedAt() + "|" + item.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(Instant publishedAt, UUID id) {}

    public record Page(
            List<PublicContentMapper.PublicContentView> items,
            String nextCursor,
            boolean hasMore) {}
}

