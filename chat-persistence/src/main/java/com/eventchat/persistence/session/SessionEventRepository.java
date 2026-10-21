package com.eventchat.persistence.session;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SessionEventRepository extends JpaRepository<SessionEvent, SessionEvent.Key> {

    /** 멱등 키 조회. 세션 안에서 (보낸 사람, 키)는 유일하다. */
    Optional<SessionEvent> findBySessionIdAndActorIdAndClientEventId(UUID sessionId, UUID actorId,
            String clientEventId);

    /** 세션 생성의 멱등 키 조회. 부분 인덱스를 타도록 타입을 리터럴로 둔다. */
    @Query("""
            select e from SessionEvent e
            where e.type = com.eventchat.domain.session.EventType.SESSION_CREATED
              and e.actorId = :actorId and e.clientEventId = :clientEventId""")
    Optional<SessionEvent> findCreatedByIdempotencyKey(UUID actorId, String clientEventId);
}
