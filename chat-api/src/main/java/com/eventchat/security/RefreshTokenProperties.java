package com.eventchat.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chat.refresh-token")
public record RefreshTokenProperties(Duration ttl, Duration cleanupAfter) {
}
