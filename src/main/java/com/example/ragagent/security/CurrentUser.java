package com.example.ragagent.security;

import java.util.Locale;

public interface CurrentUser {
    String userId();
    boolean isAuthenticated();
    Locale locale();
}
