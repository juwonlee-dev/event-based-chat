package com.eventchat.persistence.session;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionParticipantRepository extends JpaRepository<SessionParticipant, SessionParticipant.Key> {

    List<SessionParticipant> findBySessionIdOrderByJoinSeq(UUID sessionId);

    List<SessionParticipant> findBySessionIdInOrderByJoinSeq(Collection<UUID> sessionIds);
}
