package com.eventchat.persistence.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Query("select t.userId from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<UUID> findOwnerByTokenHash(byte[] tokenHash);

    /** 회전은 같은 토큰을 동시에 두 번 쓰지 못하도록 행을 잠그고 판정한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(UUID familyId, Instant now);

    @Modifying
    @Query("""
            update RefreshToken t set t.revokedAt = :now
            where t.revokedAt is null
              and t.familyId = (select o.familyId from RefreshToken o where o.tokenHash = :tokenHash and o.userId = :userId)""")
    int revokeFamilyOf(byte[] tokenHash, UUID userId, Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllOf(UUID userId, Instant now);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :threshold or t.revokedAt < :threshold")
    int deleteStale(Instant threshold);
}
