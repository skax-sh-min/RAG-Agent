package com.example.ragagent.security;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class AnonymousCurrentUser implements CurrentUser {
    @Override public String userId() { return "anonymous"; }
    @Override public boolean isAuthenticated() { return false; }
    @Override public Locale locale() { return Locale.KOREAN; }
}
