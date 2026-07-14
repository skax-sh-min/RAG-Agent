package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalModelAdvice {

    private final AppProperties props;

    public GlobalModelAdvice(AppProperties props) {
        this.props = props;
    }

    @ModelAttribute("authEnabled")
    public boolean authEnabled() {
        var cfg = (props != null) ? props.authSafe() : null;
        return cfg == null || cfg.enabled(); // null → default true (auth enabled)
    }

    /** §6.17 B안 — true only when app.auth.management-only=true (implies auth.enabled=false). */
    @ModelAttribute("managementOnly")
    public boolean managementOnly() {
        var cfg = (props != null) ? props.authSafe() : null;
        return cfg != null && cfg.managementOnly();
    }

    /**
     * Drives role-based UI gating (admin nav link, document upload/delete controls). Deliberately
     * scoped to management-only mode only — full-auth and plain no-auth modes both keep today's
     * "show everything" behavior unchanged (no regression, no scope creep into §6.15.2/§7.3's
     * territory, which own RBAC for those other modes).
     */
    @ModelAttribute("isAdmin")
    public boolean isAdmin() {
        if (!managementOnly()) return true;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
