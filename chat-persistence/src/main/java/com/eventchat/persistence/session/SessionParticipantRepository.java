package com.eventchat.persistence.session;

import com.eventchat.domain.session.SessionStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SessionParticipantRepository extends JpaRepository<SessionParticipant, SessionParticipant.Key> {

    List<SessionParticipant> findBySessionIdOrderByJoinSeq(UUID sessionId);

    List<SessionParticipant> findBySessionIdInOrderByJoinSeq(Collection<UUID> sessionIds);

    /** 연결 상태를 기록할 세션: 이 사용자가 참여 중인(active) 세션 가운데 상태가 status인 것. */
    @Query("""
            select p.sessionId from SessionParticipant p, ChatSession s
            where s.id = p.sessionId and p.userId = :userId and p.active = true and s.status = :status
            """)
    List<UUID> findSessionIdsOfActiveUser(UUID userId, SessionStatus status);

    /** 대조 작업용: 상태가 status인 세션의 참여 중인 참여자 전원의 연결 상태. */
    @Query("""
            select new com.eventchat.persistence.session.PresenceRow(p.sessionId, p.userId, p.presence, p.presenceVer)
            from SessionParticipant p, ChatSession s
            where s.id = p.sessionId and p.active = true and s.status = :status
            """)
    List<PresenceRow> findActivePresence(SessionStatus status);
}
