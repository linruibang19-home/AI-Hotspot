package com.aihotspot.core.auth;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AppUserPrincipal implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String email;
    private final String displayName;
    private final String passwordHash;
    private final String status;
    private final List<String> roles;
    private final List<GrantedAuthority> authorities;

    public AppUserPrincipal(
            UUID id,
            String email,
            String displayName,
            String passwordHash,
            String status,
            List<String> roles,
            List<String> permissions) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.status = status;
        this.roles = List.copyOf(roles);
        this.authorities = java.util.stream.Stream.concat(
                        roles.stream().map(role -> "ROLE_" + role), permissions.stream())
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    public UUID id() { return id; }
    public String displayName() { return displayName; }
    public List<String> roles() { return roles; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isEnabled() { return "ACTIVE".equals(status); }
    @Override public boolean isAccountNonLocked() { return !"SUSPENDED".equals(status); }
    @Override public boolean isAccountNonExpired() { return !"DELETED".equals(status); }
    @Override public boolean isCredentialsNonExpired() { return true; }
}
