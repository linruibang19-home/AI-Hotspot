package com.aihotspot.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aihotspot.core.api.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class ResearchFeedbackServiceTests {
    @Test
    void normalizesAndDeduplicatesFailureCategories() {
        assertThat(ResearchFeedbackService.normalizeCategories(
                List.of(" incorrect ", "MISSING_EVIDENCE", "INCORRECT")))
                .containsExactly("INCORRECT", "MISSING_EVIDENCE");
    }

    @Test
    void rejectsUnknownFailureCategory() {
        assertThatThrownBy(() -> ResearchFeedbackService.normalizeCategories(List.of("NOT_REAL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知反馈原因");
    }

    @Test
    void mapsInvalidApiFeedbackToUnprocessableEntity() {
        ResearchFeedbackService service = new ResearchFeedbackService(mock(JdbcTemplate.class));

        assertThatThrownBy(() -> service.submit(
                UUID.randomUUID(), UUID.randomUUID(), "UNHELPFUL", List.of(), null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.code()).isEqualTo("INVALID_RESEARCH_FEEDBACK");
                });
    }

    @Test
    void hidesResearchRunsNotOwnedByTheCaller() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
        ResearchFeedbackService service = new ResearchFeedbackService(jdbc);

        assertThatThrownBy(() -> service.submit(
                UUID.randomUUID(), UUID.randomUUID(), "HELPFUL", List.of(), null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo("RESEARCH_ANSWER_NOT_FOUND");
                });
    }
}
