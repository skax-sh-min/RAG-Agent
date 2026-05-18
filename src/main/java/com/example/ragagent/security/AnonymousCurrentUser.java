package com.example.ragagent.security;

import java.util.Locale;

// Superseded by SessionCurrentUser (@Component). Kept as a non-Spring fallback for unit tests.
public class AnonymousCurrentUser implements CurrentUser {
    @Override public String userId() { return "anonymous"; }
    @Override public boolean isAuthenticated() { return false; }
    @Override public Locale locale() { return Locale.KOREAN; }
}
