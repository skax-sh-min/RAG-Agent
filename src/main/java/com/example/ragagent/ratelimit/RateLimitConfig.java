package com.example.ragagent.ratelimit;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.CurrentUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean
    RateLimitFilter rateLimitFilter(AppProperties appProperties, CurrentUser currentUser) {
        return new RateLimitFilter(appProperties, currentUser);
    }
}
