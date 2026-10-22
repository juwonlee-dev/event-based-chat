package com.eventchat.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 서명 키를 아는 테스트에서 접근 토큰을 직접 만든다(만료된 토큰, 곧 만료되는 토큰).
 */
public final class TestTokens {

    private TestTokens() {
    }

    /** @param expiresIn 지금부터 만료까지(음수면 이미 만료됨) */
    public static String forge(String secret, UUID subject, String role, long expiresIn) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "event-based-chat");
        claims.put("sub", subject.toString());
        claims.put("role", role);
        claims.put("iat", Math.min(now - 5, now + expiresIn - 60));
        claims.put("exp", now + expiresIn);
        claims.put("jti", UUID.randomUUID().toString());
        String head = b64(Api.JSON.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String body = b64(Api.JSON.writeValueAsBytes(claims));
        return head + "." + body + "." + b64(hmac(secret, head + "." + body));
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
