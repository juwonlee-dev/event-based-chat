package com.eventchat.session;

import com.eventchat.domain.session.Rejection;
import com.eventchat.domain.session.SessionStatus;
import com.eventchat.persistence.session.ChatSession;
import com.eventchat.persistence.session.ChatSessionRepository;
import com.eventchat.persistence.session.SessionParticipant;
import com.eventchat.persistence.session.SessionParticipantRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionQueryService {

    private final ChatSessionRepository sessions;
    private final SessionParticipantRepository participants;

    /**
     * 대기 중인 세션은 누구나(상대를 찾아야 하므로), 그 밖에는 현재 참여자와 관리자만 본다.
     * 퇴장한 참여자는 상태와 관계없이 볼 수 없다.
     */
    public SessionView detail(UUID sessionId, UUID viewerId, boolean admin) {
        ChatSession session = sessions.findById(sessionId).orElseThrow(SessionErrors::notFound);
        List<SessionParticipant> sessionParticipants = participants.findBySessionIdOrderByJoinSeq(sessionId);
        Optional<SessionParticipant> self = sessionParticipants.stream()
                .filter(p -> p.getUserId().equals(viewerId))
                .findFirst();
        if (self.isPresent() && self.get().isLeft()) {
            throw SessionErrors.rejected(Rejection.PARTICIPANT_LEFT, session.getStatus());
        }
        if (!admin && self.isEmpty() && session.getStatus() != SessionStatus.WAITING) {
            throw SessionErrors.rejected(Rejection.NOT_PARTICIPANT, session.getStatus());
        }
        return SessionView.of(session, sessionParticipants);
    }
}
