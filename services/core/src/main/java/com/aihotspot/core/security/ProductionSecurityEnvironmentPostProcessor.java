package com.aihotspot.core.security;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

public class ProductionSecurityEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    private static final Set<String> PRODUCTION_NAMES = Set.of("prod", "production");
    private static final Set<String> WEAK_VALUES = Set.of(
            "ai_hotspot",
            "ai-hotspot",
            "change-me-before-use",
            "change-me-in-local-env",
            "local-change-me",
            "local-development-unsubscribe-secret",
            "ai-hotspot-local-email-code-pepper",
            "replace-with-a-long-random-secret-in-production");

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application) {
        List<String> violations = violations(environment);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Production security validation failed: " + String.join(", ", violations));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    static List<String> violations(Environment environment) {
        String deployment = value(environment, "AI_HOTSPOT_ENV", "ai-hotspot.environment");
        if (!PRODUCTION_NAMES.contains(deployment.toLowerCase(Locale.ROOT))) {
            return List.of();
        }

        List<String> violations = new ArrayList<>();
        requireBoolean(environment, violations, "SESSION_COOKIE_SECURE", true);
        requireBoolean(environment, violations, "AI_HOTSPOT_SMOKE_ENABLED", false);
        requireBoolean(environment, violations, "BOOTSTRAP_ADMIN_ENABLED", false);
        if (!value(environment, "BOOTSTRAP_ADMIN_PASSWORD").isBlank()) {
            violations.add("BOOTSTRAP_ADMIN_PASSWORD must be empty in production");
        }
        requireBoolean(environment, violations, "SMTP_AUTH", true);
        requireBoolean(environment, violations, "SMTP_STARTTLS", true);
        requireBoolean(environment, violations, "SMTP_STARTTLS_REQUIRED", true);
        requireHttps(environment, violations, "PUBLIC_BASE_URL");
        requireStrong(environment, violations, "POSTGRES_PASSWORD", 16);
        requireStrong(environment, violations, "REDIS_PASSWORD", 16);
        requireStrong(environment, violations, "RABBITMQ_DEFAULT_PASS", 16);
        requireStrong(environment, violations, "EMAIL_CODE_PEPPER", 32);
        requireStrong(environment, violations, "UNSUBSCRIBE_SECRET", 32);
        requireStrong(environment, violations, "SMTP_PASSWORD", 16);
        requirePresent(environment, violations, "SMTP_USERNAME");

        String mailProvider = value(environment, "MAIL_PROVIDER");
        if (!"smtp".equalsIgnoreCase(mailProvider)) {
            violations.add("MAIL_PROVIDER must be smtp");
        }
        String smtpHost = value(environment, "SMTP_HOST").toLowerCase(Locale.ROOT);
        if (smtpHost.isBlank()
                || Set.of("localhost", "127.0.0.1", "mailpit").contains(smtpHost)) {
            violations.add("SMTP_HOST must reference the production SMTP service");
        }
        return List.copyOf(violations);
    }

    private static void requireBoolean(
            Environment environment,
            List<String> violations,
            String key,
            boolean expected) {
        if (!Boolean.toString(expected).equalsIgnoreCase(value(environment, key))) {
            violations.add(key + " must be " + expected);
        }
    }

    private static void requireHttps(
            Environment environment,
            List<String> violations,
            String key) {
        try {
            URI uri = URI.create(value(environment, key));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                violations.add(key + " must be an absolute HTTPS URL");
            }
        } catch (RuntimeException error) {
            violations.add(key + " must be an absolute HTTPS URL");
        }
    }

    private static void requirePresent(
            Environment environment,
            List<String> violations,
            String key) {
        if (value(environment, key).isBlank()) {
            violations.add(key + " is required");
        }
    }

    private static void requireStrong(
            Environment environment,
            List<String> violations,
            String key,
            int minimumLength) {
        String secret = value(environment, key);
        if (secret.length() < minimumLength
                || WEAK_VALUES.contains(secret.toLowerCase(Locale.ROOT))) {
            violations.add(key + " must be replaced with a strong value");
        }
    }

    private static String value(Environment environment, String... keys) {
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (value != null) {
                return value.strip();
            }
        }
        return "";
    }
}
