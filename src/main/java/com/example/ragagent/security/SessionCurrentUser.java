package com.example.ragagent.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class SessionCurrentUser implements CurrentUser {

    @Override
    public String userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AppUserDetails user) {
            return user.getId();
        }
        return "anonymous";
    }

    @Override
    public boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AppUserDetails;
    }

    @Override
    public Locale locale() {
        return Locale.KOREAN;
    }
}
