package com.aihotspot.core.auth;

import com.aihotspot.core.api.ApiException;
import com.aihotspot.core.notification.MailProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {

    private static final Set<String> PURPOSES = Set.of("REGISTER", "LOGIN");
    private final IdentityMapper mapper;
    private final MailProvider mailProvider;
    private final SecureRandom random = new SecureRandom();
    private final String pepper;
    private final Duration lifetime;
    private final int maxAttempts;

    public EmailVerificationService(
            IdentityMapper mapper,
            MailProvider mailProvider,
            @Value("${ai-hotspot.security.email-code-pepper:ai-hotspot-local-email-code-pepper}") String pepper,
            @Value("${ai-hotspot.security.email-code-lifetime-seconds:600}") int lifetimeSeconds,
            @Value("${ai-hotspot.security.email-code-max-attempts:5}") int maxAttempts) {
        this.mapper = mapper;
        this.mailProvider = mailProvider;
        this.pepper = pepper;
        this.lifetime = Duration.ofSeconds(Math.max(120, Math.min(lifetimeSeconds, 1800)));
        this.maxAttempts = Math.max(3, Math.min(maxAttempts, 10));
    }

    @Transactional
    public void sendCode(String email, String purpose, String clientIp) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedPurpose = normalizePurpose(purpose);

        // Do not reveal whether an account exists. Registration codes are only sent to new
        // addresses; login codes are only sent to active accounts.
        IdentityMapper.UserAccount account = mapper.findUserByEmail(normalizedEmail);
        boolean shouldSend = "REGISTER".equals(normalizedPurpose)
                ? account == null
                : account != null && "ACTIVE".equals(account.status());
        if (!shouldSend) return;

        String code = "%06d".formatted(random.nextInt(1_000_000));
        Instant now = Instant.now();
        mapper.invalidateEmailChallenges(normalizedEmail, normalizedPurpose);
        mapper.insertEmailChallenge(new IdentityMapper.EmailChallenge(
                UUID.randomUUID(), normalizedEmail, normalizedPurpose,
                hashCode(normalizedEmail, normalizedPurpose, code), now.plus(lifetime), null,
                0, maxAttempts, sha256(clientIp == null ? "unknown" : clientIp), now));
        mailProvider.send(
                normalizedEmail,
                "AI Hotspot 邮箱验证码",
                emailBody(code, normalizedPurpose, lifetime.toMinutes()));
    }

    @Transactional
    public void verifyAndConsume(String email, String purpose, String code) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedPurpose = normalizePurpose(purpose);
        IdentityMapper.EmailChallenge challenge = mapper.findEmailChallengeForUpdate(normalizedEmail, normalizedPurpose);
        if (challenge == null || challenge.consumedAt() != null || challenge.expiresAt().isBefore(Instant.now())
                || challenge.failedAttempts() >= challenge.maxAttempts()) {
            throw invalidCode();
        }
        String submittedHash = hashCode(normalizedEmail, normalizedPurpose, code == null ? "" : code.strip());
        if (!MessageDigest.isEqual(
                challenge.codeHash().getBytes(StandardCharsets.UTF_8),
                submittedHash.getBytes(StandardCharsets.UTF_8))) {
            mapper.recordEmailChallengeFailure(challenge.id());
            throw invalidCode();
        }
        if (mapper.consumeEmailChallenge(challenge.id()) != 1) throw invalidCode();
    }

    private ApiException invalidCode() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMAIL_CODE_INVALID", "验证码无效、已过期或尝试次数过多");
    }

    private String normalizePurpose(String purpose) {
        String normalized = purpose == null ? "" : purpose.strip().toUpperCase();
        if (!PURPOSES.contains(normalized)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMAIL_CODE_PURPOSE_INVALID", "验证码用途不正确");
        }
        return normalized;
    }

    private String normalizeEmail(String email) {
        if (email == null || !email.contains("@")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EMAIL", "邮箱格式不正确");
        }
        return email.strip().toLowerCase();
    }

    private String hashCode(String email, String purpose, String code) {
        return sha256(pepper + "|" + purpose + "|" + email + "|" + code);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String emailBody(String code, String purpose, long minutes) {
        String action = "REGISTER".equals(purpose) ? "注册" : "登录";
        return """
                <div style="font-family:Arial,'Microsoft YaHei',sans-serif;color:#15252d;line-height:1.7">
                  <h2>AI Hotspot %s验证码</h2>
                  <p>你的验证码是：</p>
                  <p style="font-size:30px;font-weight:700;letter-spacing:8px;color:#087984">%s</p>
                  <p>验证码 %d 分钟内有效，请勿转发给他人。如果不是你本人操作，请忽略此邮件。</p>
                </div>
                """.formatted(action, code, minutes);
    }
}
