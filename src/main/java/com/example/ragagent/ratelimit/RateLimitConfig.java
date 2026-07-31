package com.example.ragagent.ratelimit;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.ClientIpResolver;
import com.example.ragagent.security.CurrentUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean
    RateLimitFilter rateLimitFilter(AppProperties appProperties,
                                    CurrentUser currentUser,
                                    ClientIpResolver clientIpResolver) {
        return new RateLimitFilter(appProperties, currentUser, clientIpResolver);
    }
}
