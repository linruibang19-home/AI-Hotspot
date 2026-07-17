package com.aihotspot.core.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);
    private final IdentityMapper mapper;
    private final IdentityService identityService;
    private final boolean enabled;
    private final String email;
    private final String password;
    private final String displayName;

    public BootstrapAdminInitializer(
            IdentityMapper mapper,
            IdentityService identityService,
            @Value("${ai-hotspot.bootstrap-admin.enabled:false}") boolean enabled,
            @Value("${ai-hotspot.bootstrap-admin.email:}") String email,
            @Value("${ai-hotspot.bootstrap-admin.password:}") String password,
            @Value("${ai-hotspot.bootstrap-admin.display-name:AI Hotspot 管理员}") String displayName) {
        this.mapper = mapper;
        this.identityService = identityService;
        this.enabled = enabled;
        this.email = email;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (email.isBlank() || password.isBlank()) throw new IllegalStateException("Bootstrap admin credentials are required when enabled");
        if (mapper.findUserByEmail(email.strip().toLowerCase()) != null) return;
        identityService.createUser(email, displayName, password, "ADMIN", null, null);
        log.warn("Development bootstrap administrator created for {}. Change the local password before shared use.", email);
    }
}
