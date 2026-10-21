package com.eventchat.security;

import com.eventchat.persistence.auth.RefreshToken;
import com.eventchat.persistence.auth.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 갱신 토큰 발급·회전·폐기.
 * <ul>
 *   <li>원문은 저장하지 않고 SHA-256 해시만 저장한다.</li>
 *   <li>회전할 때 같은 family_id와 만료 시각을 이어받는다(최초 로그인 기준 고정 만료).</li>
 *   <li>이미 회전된 토큰이 다시 쓰이면 탈취로 보고 그 계열 전체를 폐기한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final RefreshTokenProperties properties;

    @Transactional
    public Issued issue(UUID userId, ClientInfo client) {
        String raw = newRawToken();
        Instant expiresAt = Instant.now().plus(properties.ttl());
        tokens.save(RefreshToken.issue(userId, UUID.randomUUID(), hash(raw), expiresAt, client.userAgent(), client.ip()));
        return new Issued(raw, expiresAt);
    }

    /** 레이트 리밋 키를 정하려고 잠금 없이 소유자만 미리 조회한다. */
    @Transactional(readOnly = true)
    public Optional<UUID> findOwner(String raw) {
        return tokens.findOwnerByTokenHash(hash(raw));
    }

    @Transactional
    public RotationResult rotate(String raw, ClientInfo client) {
        Optional<RefreshToken> found = tokens.findByTokenHash(hash(raw));
        if (found.isEmpty()) {
            return new RotationResult.Invalid();
        }
        RefreshToken token = found.get();
        Instant now = Instant.now();
        if (token.isUsed()) {
            // 이미 회전된 토큰의 재사용: 탈취로 본다. 예외를 던지지 않아야 폐기가 커밋된다.
            tokens.revokeFamily(token.getFamilyId(), now);
            return new RotationResult.Reused(token.getUserId());
        }
        if (!token.isUsable(now)) {
            return new RotationResult.Invalid();
        }
        String next = newRawToken();
        RefreshToken rotated = tokens.save(RefreshToken.issue(token.getUserId(), token.getFamilyId(), hash(next),
                token.getExpiresAt(), client.userAgent(), client.ip()));
        token.replaceWith(rotated.getId(), now);
        return new RotationResult.Rotated(token.getUserId(), new Issued(next, token.getExpiresAt()));
    }

    /** 로그아웃: 이 토큰이 속한 계열을 폐기한다. 다른 사용자의 토큰이면 아무것도 하지 않는다. */
    @Transactional
    public void revokeFamilyOf(String raw, UUID userId) {
        tokens.revokeFamilyOf(hash(raw), userId, Instant.now());
    }

    @Transactional
    public int revokeAll(UUID userId) {
        return tokens.revokeAllOf(userId, Instant.now());
    }

    @Transactional
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void deleteStale() {
        int deleted = tokens.deleteStale(Instant.now().minus(properties.cleanupAfter()));
        if (deleted > 0) {
            log.info("deleted {} stale refresh tokens", deleted);
        }
    }

    private static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] hash(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Issued(String value, Instant expiresAt) {
    }

    public sealed interface RotationResult {

        record Rotated(UUID userId, Issued token) implements RotationResult {
        }

        record Reused(UUID userId) implements RotationResult {
        }

        record Invalid() implements RotationResult {
        }
    }
}
