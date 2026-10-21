package com.eventchat.stomp;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

/**
 * CONNECT에서 검증한 사용자. 이름은 사용자 ID라서 /user 목적지도 사용자 ID로 풀린다.
 */
public record StompPrincipal(UUID userId, String role, Instant tokenExpiresAt) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
