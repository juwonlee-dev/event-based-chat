package com.eventchat.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chat.jwt")
public record JwtProperties(String issuer, Duration accessTtl, Duration clockSkew, String secret) {

    public JwtProperties {
        // HS256 키는 32바이트 이상이어야 한다. 짧으면 기동하지 않는다.
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("chat.jwt.secret must be at least 32 bytes");
        }
    }
}
