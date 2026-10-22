package com.eventchat.persistence.session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SessionEventRepository
        extends JpaRepository<SessionEvent, SessionEvent.Key>, SessionEventSearchRepository {

    /** 멱등 키 조회. 세션 안에서 (보낸 사람, 키)는 유일하다. */
    Optional<SessionEvent> findBySessionIdAndActorIdAndClientEventId(UUID sessionId, UUID actorId,
            String clientEventId);

    /**
     * 같은 쓰기에서 함께 만든 이벤트 묶음(예: LEAVE와 뒤따르는 상태 전이). 한 트랜잭션의 이벤트는 같은 server_ts와
     * 연속된 seq를 가진다. 멱등 재전송 때 묶음 전체를 다시 발행하려고 쓴다.
     */
    List<SessionEvent> findBySessionIdAndServerTsAndSeqGreaterThanEqualOrderBySeq(UUID sessionId, Instant serverTs,
            long seq);

    /** 세션 생성의 멱등 키 조회. 부분 인덱스를 타도록 타입을 리터럴로 둔다. */
    @Query("""
            select e from SessionEvent e
            where e.type = com.eventchat.domain.session.EventType.SESSION_CREATED
              and e.actorId = :actorId and e.clientEventId = :clientEventId""")
    Optional<SessionEvent> findCreatedByIdempotencyKey(UUID actorId, String clientEventId);

    /** 이 seq까지의 마지막 메시지 번호. 읽음 커서를 저장할 때만 부른다. */
    @Query("""
            select coalesce(max(e.msgNo), 0) from SessionEvent e
            where e.sessionId = :sessionId
              and e.type = com.eventchat.domain.session.EventType.MESSAGE
              and e.seq <= :upToSeq""")
    int findLastMsgNoUpTo(UUID sessionId, long upToSeq);
}
