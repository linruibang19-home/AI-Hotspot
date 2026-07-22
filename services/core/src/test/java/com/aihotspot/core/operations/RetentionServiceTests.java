package com.aihotspot.core.operations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RetentionServiceTests {

    @Test
    void retentionUsesCurrentVerificationChallengeTable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        new RetentionService(jdbc).apply();

        verify(jdbc).update(
                "delete from iam.email_verification_challenge where created_at < now() - interval '7 days'");
    }
}
