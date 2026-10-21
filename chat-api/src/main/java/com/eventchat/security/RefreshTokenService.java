package com.eventchat.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    private final JdbcClient jdbc;
    private final RefreshTokenProperties properties;

    public Issued issue(UUID userId, ClientInfo client) {
        String raw = newRawToken();
        Instant expiresAt = Instant.now().plus(properties.ttl());
        insert(userId, UUID.randomUUID(), raw, expiresAt, client);
        return new Issued(raw, expiresAt);
    }

    /** 레이트 리밋 키를 정하려고 잠금 없이 소유자만 미리 조회한다. */
    public Optional<UUID> findOwner(String raw) {
        return jdbc.sql("SELECT user_id FROM refresh_token WHERE token_hash = ?")
                .param(hash(raw))
                .query(UUID.class)
                .optional();
    }

    @Transactional
    public RotationResult rotate(String raw, ClientInfo client) {
        Optional<StoredToken> found = jdbc.sql("""
                        SELECT id, user_id, family_id, expires_at, used_at, revoked_at
                        FROM refresh_token WHERE token_hash = ? FOR UPDATE""")
                .param(hash(raw))
                .query(StoredToken.class)
                .optional();
        if (found.isEmpty()) {
            return new RotationResult.Invalid();
        }
        StoredToken token = found.get();
        if (token.usedAt() != null) {
            // 이미 회전된 토큰의 재사용: 탈취로 본다. 예외를 던지지 않아야 폐기가 커밋된다.
            revokeFamily(token.familyId());
            return new RotationResult.Reused(token.userId());
        }
        if (token.revokedAt() != null || !token.expiresAt().toInstant().isAfter(Instant.now())) {
            return new RotationResult.Invalid();
        }
        String next = newRawToken();
        Instant expiresAt = token.expiresAt().toInstant();
        UUID nextId = insert(token.userId(), token.familyId(), next, expiresAt, client);
        jdbc.sql("UPDATE refresh_token SET used_at = now(), revoked_at = now(), replaced_by = ? WHERE id = ?")
                .params(nextId, token.id())
                .update();
        return new RotationResult.Rotated(token.userId(), new Issued(next, expiresAt));
    }

    /** 로그아웃: 이 토큰이 속한 계열을 폐기한다. 다른 사용자의 토큰이면 아무것도 하지 않는다. */
    public void revokeFamilyOf(String raw, UUID userId) {
        jdbc.sql("""
                        UPDATE refresh_token SET revoked_at = now()
                        WHERE revoked_at IS NULL
                          AND family_id = (SELECT family_id FROM refresh_token WHERE token_hash = ? AND user_id = ?)""")
                .params(hash(raw), userId)
                .update();
    }

    public int revokeAll(UUID userId) {
        return jdbc.sql("UPDATE refresh_token SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL")
                .param(userId)
                .update();
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void deleteStale() {
        Timestamp threshold = Timestamp.from(Instant.now().minus(properties.cleanupAfter()));
        int deleted = jdbc.sql("DELETE FROM refresh_token WHERE expires_at < ? OR revoked_at < ?")
                .params(threshold, threshold)
                .update();
        if (deleted > 0) {
            log.info("deleted {} stale refresh tokens", deleted);
        }
    }

    private void revokeFamily(UUID familyId) {
        jdbc.sql("UPDATE refresh_token SET revoked_at = now() WHERE family_id = ? AND revoked_at IS NULL")
                .param(familyId)
                .update();
    }

    private UUID insert(UUID userId, UUID familyId, String raw, Instant expiresAt, ClientInfo client) {
        return jdbc.sql("""
                        INSERT INTO refresh_token (user_id, family_id, token_hash, expires_at, user_agent, ip)
                        VALUES (?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(userId, familyId, hash(raw), Timestamp.from(expiresAt), client.userAgent(), client.ip())
                .query(UUID.class)
                .single();
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

    private record StoredToken(UUID id, UUID userId, UUID familyId, OffsetDateTime expiresAt,
            OffsetDateTime usedAt, OffsetDateTime revokedAt) {
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
