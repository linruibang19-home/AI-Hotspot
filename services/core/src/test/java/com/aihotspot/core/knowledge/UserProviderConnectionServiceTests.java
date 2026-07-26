package com.aihotspot.core.knowledge;

import com.aihotspot.core.api.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProviderConnectionServiceTests {

    @Test
    void disabledPersonalRouteFailsClosedBeforeRetrieval() {
        Fixture fixture = fixture(false);
        when(fixture.jdbc.queryForList(anyString(), eq(fixture.userId))).thenReturn(List.of(Map.of(
                "assignment_status", "DISABLED",
                "connection_status", "DISABLED",
                "platform_fallback_enabled", false)));

        assertThatThrownBy(() -> fixture.service.acquireRagPermit(fixture.userId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("USER_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void explicitFallbackAllowsPlatformRouteWhenPersonalConnectionIsDisabled() {
        Fixture fixture = fixture(false);
        when(fixture.jdbc.queryForList(anyString(), eq(fixture.userId))).thenReturn(List.of(Map.of(
                "assignment_status", "DISABLED",
                "connection_status", "DISABLED",
                "platform_fallback_enabled", true)));

        try (var ignored = fixture.service.acquireRagPermit(fixture.userId)) {
            assertThat(ignored).isNotNull();
        }
    }

    @Test
    void siteCanRequireAUserGenerationKey() {
        Fixture fixture = fixture(true);
        when(fixture.jdbc.queryForList(anyString(), eq(fixture.userId))).thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.acquireRagPermit(fixture.userId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("USER_PROVIDER_REQUIRED"));
    }

    @Test
    void activeRouteRejectsConcurrentResearchForTheSameUser() {
        Fixture fixture = fixture(false);
        stubActiveRoute(fixture, 50, 5_000_000L);
        when(fixture.jdbc.queryForMap(anyString(), eq(fixture.userId), eq(fixture.userId)))
                .thenReturn(Map.of(
                        "today_queries", 1L,
                        "month_tokens", 100L,
                        "month_cost", 0.0,
                        "user_provider_calls", 1L));
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(fixture.redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> fixture.service.acquireRagPermit(fixture.userId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("BYOK_CONCURRENT_QUERY"));
        verify(values).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void activeRouteEnforcesDailyQueryQuotaBeforeTakingTheLock() {
        Fixture fixture = fixture(false);
        stubActiveRoute(fixture, 2, 5_000_000L);
        when(fixture.jdbc.queryForMap(anyString(), eq(fixture.userId), eq(fixture.userId)))
                .thenReturn(Map.of(
                        "today_queries", 2L,
                        "month_tokens", 100L,
                        "month_cost", 0.0,
                        "user_provider_calls", 2L));

        assertThatThrownBy(() -> fixture.service.acquireRagPermit(fixture.userId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("BYOK_DAILY_QUOTA_EXCEEDED"));
    }

    @Test
    void activeRouteEnforcesMonthlyTokenQuotaBeforeTakingTheLock() {
        Fixture fixture = fixture(false);
        stubActiveRoute(fixture, 50, 1_000L);
        when(fixture.jdbc.queryForMap(anyString(), eq(fixture.userId), eq(fixture.userId)))
                .thenReturn(Map.of(
                        "today_queries", 1L,
                        "month_tokens", 1_000L,
                        "month_cost", 0.0,
                        "user_provider_calls", 1L));

        assertThatThrownBy(() -> fixture.service.acquireRagPermit(fixture.userId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("BYOK_MONTHLY_TOKEN_EXCEEDED"));
    }

    private static void stubActiveRoute(Fixture fixture, int dailyLimit, long monthlyLimit) {
        List<Map<String, Object>> route = List.of(Map.of(
                "assignment_status", "ACTIVE",
                "connection_status", "ACTIVE",
                "platform_fallback_enabled", false));
        List<Map<String, Object>> policy = List.of(Map.of(
                "daily_query_limit", dailyLimit,
                "monthly_token_limit", monthlyLimit,
                "platform_fallback_enabled", false,
                "status", "ACTIVE"));
        when(fixture.jdbc.queryForList(anyString(), eq(fixture.userId)))
                .thenReturn(route, policy);
    }

    private static Fixture fixture(boolean requireKey) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        UUID userId = UUID.randomUUID();
        UserProviderConnectionService service = new UserProviderConnectionService(
                jdbc,
                mock(ProviderCredentialCipher.class),
                new ProviderEndpointPolicy("development"),
                redis,
                "http://localhost:8000",
                "test-internal-token",
                requireKey);
        return new Fixture(jdbc, redis, service, userId);
    }

    private record Fixture(JdbcTemplate jdbc, StringRedisTemplate redis,
                           UserProviderConnectionService service, UUID userId) {}
}
