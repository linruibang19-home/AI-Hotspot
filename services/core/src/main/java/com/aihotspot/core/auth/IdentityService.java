package com.aihotspot.core.auth;

import com.aihotspot.core.api.ApiException;
import com.aihotspot.core.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdentityService {

    private static final Set<String> ROLES = Set.of("USER", "EDITOR", "OPERATOR", "ADMIN");
    private static final Set<String> INVITABLE_ROLES = Set.of("USER", "EDITOR", "OPERATOR");
    private final IdentityMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public IdentityService(IdentityMapper mapper, PasswordEncoder passwordEncoder, AuditService audit, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IdentityMapper.UserAccount createUser(
            String email, String displayName, String password, String role, UUID actorId, HttpServletRequest request) {
        validateRole(role, ROLES);
        validatePassword(password);
        String normalizedEmail = normalizeEmail(email);
        if (mapper.findUserByEmail(normalizedEmail) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "该邮箱已存在");
        }
        UUID id = UUID.randomUUID();
        IdentityMapper.UserAccount account = new IdentityMapper.UserAccount(
                id, normalizedEmail, displayName.strip(), passwordEncoder.encode(password), "ACTIVE", "zh-CN",
                "Asia/Shanghai", 0, actorId, Instant.now(), Instant.now(), null);
        mapper.insertUser(account);
        mapper.assignRole(id, role, actorId);
        audit.record(actorId, "USER_CREATED", "USER_ACCOUNT", id, null,
                json(Map.of("email", normalizedEmail, "role", role, "status", "ACTIVE")), request);
        return mapper.findUserById(id);
    }

    @Transactional
    public InvitationCreated createInvitation(
            String role, int maxUses, Instant expiresAt, UUID actorId, HttpServletRequest request) {
        validateRole(role, INVITABLE_ROLES);
        if (maxUses < 1 || maxUses > 100) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MAX_USES", "邀请码使用次数必须为 1 到 100");
        if (expiresAt.isBefore(Instant.now().plus(Duration.ofMinutes(5))) || expiresAt.isAfter(Instant.now().plus(Duration.ofDays(90)))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EXPIRY", "邀请码有效期必须在 5 分钟到 90 天之间");
        }
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        UUID id = UUID.randomUUID();
        mapper.insertInvitation(new IdentityMapper.Invitation(
                id, sha256(token), actorId, role, maxUses, 0, expiresAt, "ACTIVE", 0, Instant.now()));
        audit.record(actorId, "INVITATION_CREATED", "INVITATION_CODE", id, null,
                json(Map.of("defaultRole", role, "maxUses", maxUses, "expiresAt", expiresAt.toString())), request);
        return new InvitationCreated(id, token, role, maxUses, expiresAt);
    }

    @Transactional
    public IdentityMapper.UserAccount register(
            String token, String email, String displayName, String password, HttpServletRequest request) {
        validatePassword(password);
        IdentityMapper.Invitation invitation = mapper.findInvitationForUpdate(sha256(token));
        if (invitation == null || !"ACTIVE".equals(invitation.status()) || invitation.expiresAt().isBefore(Instant.now()) || invitation.usedCount() >= invitation.maxUses()) {
            throw new ApiException(HttpStatus.GONE, "INVITATION_INVALID", "邀请码无效、已过期或已用完");
        }
        String normalizedEmail = normalizeEmail(email);
        if (mapper.findUserByEmail(normalizedEmail) != null) throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "该邮箱已存在");
        UUID id = UUID.randomUUID();
        mapper.insertUser(new IdentityMapper.UserAccount(
                id, normalizedEmail, displayName.strip(), passwordEncoder.encode(password), "ACTIVE", "zh-CN",
                "Asia/Shanghai", 0, invitation.createdBy(), Instant.now(), Instant.now(), null));
        mapper.assignRole(id, invitation.defaultRole(), invitation.createdBy());
        if (mapper.consumeInvitation(invitation.id(), invitation.version()) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_CONFLICT", "邀请码已被其他请求使用，请重试");
        }
        audit.record(id, "INVITATION_REGISTERED", "USER_ACCOUNT", id, null,
                json(Map.of("email", normalizedEmail, "role", invitation.defaultRole(), "invitationId", invitation.id())), request);
        return mapper.findUserById(id);
    }

    @Transactional
    public IdentityMapper.UserAccount registerPublic(
            String email, String displayName, String code, EmailVerificationService verificationService,
            HttpServletRequest request) {
        String normalizedEmail = normalizeEmail(email);
        verificationService.verifyAndConsume(normalizedEmail, "REGISTER", code);
        if (mapper.findUserByEmail(normalizedEmail) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "该邮箱已存在，请直接登录");
        }
        UUID id = UUID.randomUUID();
        mapper.insertUser(new IdentityMapper.UserAccount(
                id, normalizedEmail, displayName.strip(), null, "ACTIVE", "zh-CN",
                "Asia/Shanghai", 0, null, Instant.now(), Instant.now(), null));
        mapper.assignRole(id, "USER", null);
        audit.record(id, "PUBLIC_EMAIL_REGISTERED", "USER_ACCOUNT", id, null,
                json(Map.of("email", normalizedEmail, "role", "USER")), request);
        return mapper.findUserById(id);
    }

    @Transactional
    public void markLogin(UUID userId) {
        mapper.updateLastLogin(userId);
    }

    public List<IdentityMapper.AdminUserView> listUsers(int limit) { return mapper.listUsers(Math.min(Math.max(limit, 1), 100)); }
    public List<IdentityMapper.InvitationView> listInvitations(int limit) { return mapper.listInvitations(Math.min(Math.max(limit, 1), 100)); }

    private String normalizeEmail(String email) {
        if (email == null || !email.contains("@")) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EMAIL", "邮箱格式不正确");
        return email.strip().toLowerCase();
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 72) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PASSWORD", "密码长度必须为 12 到 72 个字符");
        }
    }

    private void validateRole(String role, Set<String> allowed) {
        if (!allowed.contains(role)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ROLE", "角色不允许用于当前操作");
    }

    private String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Cannot serialize audit payload", exception); }
    }

    public record InvitationCreated(UUID id, String token, String defaultRole, int maxUses, Instant expiresAt) {}
}
