package com.aihotspot.core.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final IdentityMapper mapper;

    public DatabaseUserDetailsService(IdentityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        IdentityMapper.UserAccount account = mapper.findUserByEmail(username.strip().toLowerCase());
        if (account == null || account.passwordHash() == null) {
            throw new UsernameNotFoundException("用户名或密码错误");
        }
        return new AppUserPrincipal(
                account.id(), account.email(), account.displayName(), account.passwordHash(), account.status(),
                mapper.findRoleCodes(account.id()), mapper.findPermissionCodes(account.id()));
    }
}
