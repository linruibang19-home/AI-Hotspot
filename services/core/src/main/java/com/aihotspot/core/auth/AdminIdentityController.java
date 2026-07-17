package com.aihotspot.core.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminIdentityController {

    private final IdentityService identityService;

    public AdminIdentityController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @GetMapping("/users")
    public List<IdentityMapper.AdminUserView> users(@RequestParam(defaultValue = "50") int limit) {
        return identityService.listUsers(limit);
    }

    @PostMapping("/users")
    public IdentityMapper.UserAccount createUser(
            @Valid @RequestBody CreateUserRequest body,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest request) {
        return identityService.createUser(body.email(), body.displayName(), body.password(), body.role(), principal.id(), request);
    }

    @GetMapping("/invitations")
    public List<IdentityMapper.InvitationView> invitations(@RequestParam(defaultValue = "50") int limit) {
        return identityService.listInvitations(limit);
    }

    @PostMapping("/invitations")
    public IdentityService.InvitationCreated createInvitation(
            @Valid @RequestBody CreateInvitationRequest body,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest request) {
        return identityService.createInvitation(body.defaultRole(), body.maxUses(), body.expiresAt(), principal.id(), request);
    }

    public record CreateUserRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 2, max = 80) String displayName,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotBlank String role) {}

    public record CreateInvitationRequest(
            @NotBlank String defaultRole,
            @Min(1) @Max(100) int maxUses,
            @NotNull Instant expiresAt) {}
}
