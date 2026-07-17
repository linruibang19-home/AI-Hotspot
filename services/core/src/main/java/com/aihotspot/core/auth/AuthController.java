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
    private final RateLimitService rateLimit;
    private final AuditService audit;
    private final int loginMaxFailures;
    private final int loginWindowSeconds;
    private final int invitationMaxAttempts;
    private final int invitationWindowSeconds;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository contextRepository,
            IdentityService identityService,
            RateLimitService rateLimit,
            AuditService audit,
            @Value("${ai-hotspot.security.login-max-failures:5}") int loginMaxFailures,
            @Value("${ai-hotspot.security.login-window-seconds:900}") int loginWindowSeconds,
            @Value("${ai-hotspot.security.invitation-max-attempts:10}") int invitationMaxAttempts,
            @Value("${ai-hotspot.security.invitation-window-seconds:3600}") int invitationWindowSeconds) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.identityService = identityService;
        this.rateLimit = rateLimit;
        this.audit = audit;
        this.loginMaxFailures = loginMaxFailures;
        this.loginWindowSeconds = loginWindowSeconds;
        this.invitationMaxAttempts = invitationMaxAttempts;
        this.invitationWindowSeconds = invitationWindowSeconds;
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

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
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
