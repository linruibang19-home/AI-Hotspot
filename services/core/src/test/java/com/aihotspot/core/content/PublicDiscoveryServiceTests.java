package com.aihotspot.core.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aihotspot.core.api.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicDiscoveryServiceTests {
    @Test
    void searchNormalizesFiltersAndCapsTheLimit() {
        PublicDiscoveryMapper mapper = mock(PublicDiscoveryMapper.class);
        when(mapper.search(any(), any(), any(), any(Integer.class))).thenReturn(List.of());

        new PublicDiscoveryService(mapper).search("  OpenAI  ", "official", "all", 500);

        verify(mapper).search("OpenAI", "OFFICIAL", null, 100);
    }

    @Test
    void rejectsTooShortSearch() {
        PublicDiscoveryMapper mapper = mock(PublicDiscoveryMapper.class);
        assertThatThrownBy(() -> new PublicDiscoveryService(mapper).search("A", null, null, 20))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SEARCH_QUERY_TOO_SHORT"));
    }

    @Test
    void missingTopicAndEventAreNotExposed() {
        PublicDiscoveryMapper mapper = mock(PublicDiscoveryMapper.class);
        PublicDiscoveryService service = new PublicDiscoveryService(mapper);

        assertThatThrownBy(() -> service.topic("missing", 20))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("TOPIC_NOT_FOUND"));
        assertThatThrownBy(() -> service.event(UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PUBLIC_EVENT_NOT_FOUND"));
    }
}
