package com.example.ragagent.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class AppUserDetails implements UserDetails {

    private final String id;
    private final String email;
    private final String passwordHash;
    private final String displayName;
    private final String role;
    private final boolean enabled;
    private final boolean locked;

    public AppUserDetails(String id, String email, String passwordHash,
                          String displayName, String role, boolean enabled, boolean locked) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.enabled = enabled;
        this.locked = locked;
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName != null ? displayName : email; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonLocked() { return !locked; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
}
