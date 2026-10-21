package com.eventchat.persistence.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 세션 상태 스냅샷(읽기 전용 매핑). 쓰기는 조건부 UPSERT({@link SessionSnapshotRepository#upsert})로만 한다.
 */
@Entity
@Immutable
@Table(name = "session_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionSnapshot {

    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "last_event_ts", nullable = false)
    private Instant lastEventTs;

    @Column(name = "reducer_version", nullable = false)
    private short reducerVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> state;

    @Column(name = "state_hash", nullable = false)
    private String stateHash;

    @Column(name = "build_count", nullable = false)
    private int buildCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
