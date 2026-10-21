package com.eventchat.persistence.session;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SessionSnapshotRepository extends JpaRepository<SessionSnapshot, UUID> {

    /**
     * 더 새로운 seq이거나 리듀서 버전이 다를 때만 덮어쓴다. 여러 작업자가 동시에 만들어도 seq가 뒤로 가지 않는다.
     *
     * @return 쓴 행 수. 이미 같거나 더 새로운 스냅샷이 있으면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO session_snapshot (session_id, last_seq, last_event_ts, reducer_version, state, state_hash)
            VALUES (:sessionId, :lastSeq, :lastEventTs, :reducerVersion, CAST(:state AS jsonb), :stateHash)
            ON CONFLICT (session_id) DO UPDATE SET
                last_seq = EXCLUDED.last_seq,
                last_event_ts = EXCLUDED.last_event_ts,
                reducer_version = EXCLUDED.reducer_version,
                state = EXCLUDED.state,
                state_hash = EXCLUDED.state_hash,
                build_count = session_snapshot.build_count + 1,
                updated_at = now()
            WHERE session_snapshot.last_seq < EXCLUDED.last_seq
               OR session_snapshot.reducer_version <> EXCLUDED.reducer_version
            """, nativeQuery = true)
    int upsert(UUID sessionId, long lastSeq, Instant lastEventTs, short reducerVersion, String state,
            String stateHash);
}
