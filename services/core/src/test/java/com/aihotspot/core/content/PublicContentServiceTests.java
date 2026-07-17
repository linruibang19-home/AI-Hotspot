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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicContentServiceTests {

    @Test
    void listUsesOneExtraRowAndBuildsStableCursor() {
        PublicContentMapper mapper = mock(PublicContentMapper.class);
        Instant publishedAt = Instant.parse("2026-07-17T08:00:00Z");
        var first = content(UUID.randomUUID(), publishedAt);
        var second = content(UUID.randomUUID(), publishedAt.minusSeconds(60));
        var extra = content(UUID.randomUUID(), publishedAt.minusSeconds(120));
        when(mapper.list(false, null, null, 3)).thenReturn(List.of(first, second, extra));

        PublicContentService.Page page = new PublicContentService(mapper).list(false, null, 2);

        assertThat(page.items()).containsExactly(first, second);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        new PublicContentService(mapper).list(false, page.nextCursor(), 2);
        verify(mapper).list(false, second.publishedAt(), second.id(), 3);
    }

    @Test
    void rejectsMalformedCursorBeforeQueryingDatabase() {
        PublicContentMapper mapper = mock(PublicContentMapper.class);
        PublicContentService service = new PublicContentService(mapper);

        assertThatThrownBy(() -> service.list(false, "not-a-cursor", 20))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_CURSOR"));
    }

    @Test
    void detailNeverReturnsNonPublicRows() {
        PublicContentMapper mapper = mock(PublicContentMapper.class);
        UUID id = UUID.randomUUID();
        when(mapper.findPublicById(id)).thenReturn(null);

        assertThatThrownBy(() -> new PublicContentService(mapper).detail(id))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PUBLIC_CONTENT_NOT_FOUND"));
        verify(mapper).findPublicById(eq(id));
    }

    private PublicContentMapper.PublicContentView content(UUID id, Instant publishedAt) {
        return new PublicContentMapper.PublicContentView(
                id, "Source", "MEDIA", "OFFICIAL", "NEWS", "标题", "Original",
                "摘要", "推荐理由", "https://example.com/item", "https://example.com/item",
                "VERIFIED", BigDecimal.valueOf(88), false, publishedAt, publishedAt);
    }
}
