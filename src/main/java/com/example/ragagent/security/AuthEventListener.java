package com.example.ragagent.security;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

@Component
public class AuthEventListener {

    // Values are restated in messages.properties/messages_ko.properties (auth.login.error) —
    // update both message keys if these change.
    private static final int MAX_ATTEMPTS  = 5;
    private static final int LOCK_MINUTES  = 15;

    private final SqliteUserDetailsService userService;

    public AuthEventListener(SqliteUserDetailsService userService) {
        this.userService = userService;
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        // email might not exist — incrementFailedCount silently ignores unknown emails
        userService.incrementFailedCount(event.getAuthentication().getName(), MAX_ATTEMPTS, LOCK_MINUTES);
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        userService.resetFailedCount(event.getAuthentication().getName());
    }
}
