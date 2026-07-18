package com.aihotspot.core.auth;

import com.aihotspot.core.api.ApiException;
import com.aihotspot.core.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final IdentityService identityService;
    private final EmailVerificationService emailVerificationService;
    private final DatabaseUserDetailsService userDetailsService;
    private final RateLimitService rateLimit;
    private final AuditService audit;
    private final int loginMaxFailures;
    private final int loginWindowSeconds;
    private final int invitationMaxAttempts;
    private final int invitationWindowSeconds;
    private final int emailCodeMaxRequests;
    private final int emailCodeWindowSeconds;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository contextRepository,
            IdentityService identityService,
            EmailVerificationService emailVerificationService,
            DatabaseUserDetailsService userDetailsService,
            RateLimitService rateLimit,
            AuditService audit,
            @Value("${ai-hotspot.security.login-max-failures:5}") int loginMaxFailures,
            @Value("${ai-hotspot.security.login-window-seconds:900}") int loginWindowSeconds,
            @Value("${ai-hotspot.security.invitation-max-attempts:10}") int invitationMaxAttempts,
            @Value("${ai-hotspot.security.invitation-window-seconds:3600}") int invitationWindowSeconds,
            @Value("${ai-hotspot.security.email-code-max-requests:5}") int emailCodeMaxRequests,
            @Value("${ai-hotspot.security.email-code-window-seconds:900}") int emailCodeWindowSeconds) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.identityService = identityService;
        this.emailVerificationService = emailVerificationService;
        this.userDetailsService = userDetailsService;
        this.rateLimit = rateLimit;
        this.audit = audit;
        this.loginMaxFailures = loginMaxFailures;
        this.loginWindowSeconds = loginWindowSeconds;
        this.invitationMaxAttempts = invitationMaxAttempts;
        this.invitationWindowSeconds = invitationWindowSeconds;
        this.emailCodeMaxRequests = emailCodeMaxRequests;
        this.emailCodeWindowSeconds = emailCodeWindowSeconds;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "parameterName", token.getParameterName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public CurrentUser login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String email = body.email().strip().toLowerCase();
        String rateKey = clientIp(request) + ":" + email;
        if (!rateLimit.allowed("login", rateKey, loginMaxFailures, Duration.ofSeconds(loginWindowSeconds))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "登录尝试过多，请稍后再试");
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, body.password()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);
            AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
            identityService.markLogin(principal.id());
            rateLimit.clear("login", rateKey);
            audit.record(principal.id(), "LOGIN_SUCCEEDED", "USER_ACCOUNT", principal.id(), null, null, request);
            return CurrentUser.from(principal);
        } catch (BadCredentialsException exception) {
            audit.record(null, "LOGIN_FAILED", "USER_ACCOUNT", null, null, null, request);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
    }

    @PostMapping("/logout")
    public Map<String, String> logout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            audit.record(principal.id(), "LOGOUT", "USER_ACCOUNT", principal.id(), null, null, request);
        }
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return Map.of("status", "LOGGED_OUT");
    }

    @GetMapping("/me")
    public CurrentUser me(Authentication authentication) {
        return CurrentUser.from((AppUserPrincipal) authentication.getPrincipal());
    }

    @PostMapping("/email-codes")
    public Map<String, String> requestEmailCode(
            @Valid @RequestBody EmailCodeRequest body,
            HttpServletRequest request) {
        String email = body.email().strip().toLowerCase();
        String purpose = body.purpose().strip().toUpperCase();
        String ip = clientIp(request);
        if (!rateLimit.allowed("email-code-ip", ip, emailCodeMaxRequests * 3, Duration.ofSeconds(emailCodeWindowSeconds))
                || !rateLimit.allowed("email-code-address", email + ":" + purpose, emailCodeMaxRequests, Duration.ofSeconds(emailCodeWindowSeconds))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_CODE_RATE_LIMITED", "验证码发送过于频繁，请稍后再试");
        }
        emailVerificationService.sendCode(email, purpose, ip);
        return Map.of("status", "ACCEPTED", "detail", "如果该邮箱可用于当前操作，验证码邮件已发送");
    }

    @PostMapping("/register")
    public CurrentUser registerPublic(
            @Valid @RequestBody PublicRegisterRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        IdentityMapper.UserAccount account = identityService.registerPublic(
                body.email(), body.displayName(), body.code(), emailVerificationService, request);
        AppUserPrincipal principal = userDetailsService.loadActivePrincipal(account.email());
        establishSession(principal, request, response);
        audit.record(principal.id(), "REGISTER_SESSION_STARTED", "USER_ACCOUNT", principal.id(), null, null, request);
        return CurrentUser.from(principal);
    }

    @PostMapping("/code-login")
    public CurrentUser codeLogin(
            @Valid @RequestBody CodeLoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        String email = body.email().strip().toLowerCase();
        String rateKey = clientIp(request) + ":" + email;
        if (!rateLimit.allowed("code-login", rateKey, loginMaxFailures, Duration.ofSeconds(loginWindowSeconds))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "登录尝试过多，请稍后再试");
        }
        emailVerificationService.verifyAndConsume(email, "LOGIN", body.code());
        AppUserPrincipal principal;
        try {
            principal = userDetailsService.loadActivePrincipal(email);
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_EMAIL_CODE", "邮箱或验证码错误");
        }
        establishSession(principal, request, response);
        identityService.markLogin(principal.id());
        rateLimit.clear("code-login", rateKey);
        audit.record(principal.id(), "EMAIL_CODE_LOGIN_SUCCEEDED", "USER_ACCOUNT", principal.id(), null, null, request);
        return CurrentUser.from(principal);
    }

    @PostMapping("/invitations/{token}/register")
    public CurrentUser register(
            @org.springframework.web.bind.annotation.PathVariable String token,
            @Valid @RequestBody RegisterRequest body,
            HttpServletRequest request) {
        if (!rateLimit.allowed("invitation", clientIp(request), invitationMaxAttempts, Duration.ofSeconds(invitationWindowSeconds))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "INVITATION_RATE_LIMITED", "注册尝试过多，请稍后再试");
        }
        IdentityMapper.UserAccount account = identityService.register(token, body.email(), body.displayName(), body.password(), request);
        return new CurrentUser(account.id(), account.email(), account.displayName(), List.of(), List.of());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",", 2)[0].trim();
    }

    private void establishSession(AppUserPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
    public record EmailCodeRequest(@Email @NotBlank String email, @NotBlank String purpose) {}
    public record CodeLoginRequest(
            @Email @NotBlank String email,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "\\d{6}") String code) {}
    public record PublicRegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 2, max = 80) String displayName,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "\\d{6}") String code) {}
    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 2, max = 80) String displayName,
            @NotBlank @Size(min = 12, max = 72) String password) {}
    public record CurrentUser(UUID id, String email, String displayName, List<String> roles, List<String> permissions) {
        static CurrentUser from(AppUserPrincipal principal) {
            List<String> permissions = principal.getAuthorities().stream()
                    .map(authority -> authority.getAuthority())
                    .filter(authority -> !authority.startsWith("ROLE_"))
                    .sorted()
                    .toList();
            return new CurrentUser(principal.id(), principal.getUsername(), principal.displayName(), principal.roles(), permissions);
        }
    }
}
