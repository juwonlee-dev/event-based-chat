package com.eventchat.session;

import com.eventchat.domain.session.SessionStatus;
import com.eventchat.domain.session.TransitionReason;
import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
import com.eventchat.persistence.session.ChatSession;
import com.eventchat.persistence.session.SessionParticipant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 생성·참여·퇴장·종료·상세가 함께 쓰는 세션 응답.
 */
public record SessionView(UUID id, String title, Category category, SessionStatus status, UUID createdBy,
        Instant createdAt, Instant startedAt, Instant endedAt, TransitionReason endReason, long lastSeq,
        List<ParticipantView> participants) {

    public static SessionView of(ChatSession session, List<SessionParticipant> participants) {
        return new SessionView(session.getId(), session.getTitle(), session.getCategory(), session.getStatus(),
                session.getCreatedBy(), session.getCreatedAt(), session.getStartedAt(), session.getEndedAt(),
                session.getEndReason(), session.getLastSeq(),
                participants.stream().map(ParticipantView::of).toList());
    }

    public record ParticipantView(UUID userId, String displayName, Role role, Category category,
            ParticipantState state, Instant joinedAt, Instant leftAt) {

        static ParticipantView of(SessionParticipant p) {
            return new ParticipantView(p.getUserId(), p.getDisplayName(), p.getRole(), p.getCategory(),
                    ParticipantState.of(p), p.getJoinedAt(), p.getLeftAt());
        }
    }

    public enum ParticipantState {
        JOINED,
        LEFT;

        static ParticipantState of(SessionParticipant p) {
            return p.isLeft() ? LEFT : JOINED;
        }
    }
}
