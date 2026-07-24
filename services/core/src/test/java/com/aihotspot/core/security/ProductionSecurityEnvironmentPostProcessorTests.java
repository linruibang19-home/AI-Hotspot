package com.aihotspot.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSecurityEnvironmentPostProcessorTests {

    @Test
    void developmentDoesNotRequireProductionCredentials() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AI_HOTSPOT_ENV", "development");

        assertThat(ProductionSecurityEnvironmentPostProcessor.violations(environment)).isEmpty();
    }

    @Test
    void productionRejectsLocalDefaultsWithoutEchoingSecretValues() {
        MockEnvironment environment = environment(Map.ofEntries(
                Map.entry("SESSION_COOKIE_SECURE", "false"),
                Map.entry("AI_HOTSPOT_SMOKE_ENABLED", "true"),
                Map.entry("BOOTSTRAP_ADMIN_ENABLED", "true"),
                Map.entry("BOOTSTRAP_ADMIN_PASSWORD", "change-me-before-use"),
                Map.entry("PUBLIC_BASE_URL", "http://127.0.0.1:3000"),
                Map.entry("POSTGRES_PASSWORD", "change-me-in-local-env"),
                Map.entry("REDIS_PASSWORD", "change-me-in-local-env"),
                Map.entry("RABBITMQ_DEFAULT_PASS", "change-me-in-local-env"),
                Map.entry("EMAIL_CODE_PEPPER", "ai-hotspot-local-email-code-pepper"),
                Map.entry("UNSUBSCRIBE_SECRET", "local-development-unsubscribe-secret"),
                Map.entry("MAIL_PROVIDER", "smtp"),
                Map.entry("SMTP_HOST", "mailpit"),
                Map.entry("SMTP_AUTH", "false"),
                Map.entry("SMTP_STARTTLS", "false"),
                Map.entry("SMTP_STARTTLS_REQUIRED", "false"),
                Map.entry("SMTP_USERNAME", ""),
                Map.entry("SMTP_PASSWORD", "short")));

        String result = String.join(
                ", ", ProductionSecurityEnvironmentPostProcessor.violations(environment));

        assertThat(result)
                .contains("SESSION_COOKIE_SECURE", "POSTGRES_PASSWORD", "SMTP_HOST")
                .doesNotContain("change-me-in-local-env")
                .doesNotContain("local-development-unsubscribe-secret");
    }

    @Test
    void productionAcceptsHardenedConfiguration() {
        MockEnvironment environment = environment(Map.ofEntries(
                Map.entry("SESSION_COOKIE_SECURE", "true"),
                Map.entry("AI_HOTSPOT_SMOKE_ENABLED", "false"),
                Map.entry("BOOTSTRAP_ADMIN_ENABLED", "false"),
                Map.entry("BOOTSTRAP_ADMIN_PASSWORD", ""),
                Map.entry("PUBLIC_BASE_URL", "https://ai.example.com"),
                Map.entry("POSTGRES_PASSWORD", "postgres-credential-2026"),
                Map.entry("REDIS_PASSWORD", "redis-credential-2026"),
                Map.entry("RABBITMQ_DEFAULT_PASS", "rabbit-credential-2026"),
                Map.entry("EMAIL_CODE_PEPPER", "email-code-pepper-with-32-characters"),
                Map.entry("UNSUBSCRIBE_SECRET", "unsubscribe-secret-with-32-characters"),
                Map.entry("MAIL_PROVIDER", "smtp"),
                Map.entry("SMTP_HOST", "smtp.example.com"),
                Map.entry("SMTP_AUTH", "true"),
                Map.entry("SMTP_STARTTLS", "true"),
                Map.entry("SMTP_STARTTLS_REQUIRED", "true"),
                Map.entry("SMTP_USERNAME", "mailer@example.com"),
                Map.entry("SMTP_PASSWORD", "smtp-credential-2026")));

        assertThat(ProductionSecurityEnvironmentPostProcessor.violations(environment)).isEmpty();
    }

    private static MockEnvironment environment(Map<String, String> properties) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AI_HOTSPOT_ENV", "production");
        properties.forEach(environment::setProperty);
        return environment;
    }
}
