package com.eventchat.session;

import com.eventchat.domain.session.SessionStatus;
import com.eventchat.domain.session.TransitionReason;
import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
import com.eventchat.persistence.session.SessionParticipant;
import com.eventchat.persistence.session.SessionSummaryRow;
import com.eventchat.session.SessionView.ParticipantState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 목록 항목. 모든 사용자가 보는 목록이라 대화 내용 없이 메타데이터만 싣는다.
 */
public record SessionSummary(UUID id, String title, Category category, SessionStatus status, UUID createdBy,
        Instant createdAt, Instant startedAt, Instant endedAt, TransitionReason endReason, Instant lastEventAt,
        int messageCount, List<Participant> participants) {

    static SessionSummary of(SessionSummaryRow row, List<SessionParticipant> participants) {
        return new SessionSummary(row.id(), row.title(), row.category(), row.status(), row.createdBy(),
                row.createdAt(), row.startedAt(), row.endedAt(), row.endReason(), row.lastEventAt(),
                row.messageCount(), participants.stream().map(Participant::of).toList());
    }

    public record Participant(UUID userId, String displayName, Role role, Category category,
            ParticipantState state) {

        static Participant of(SessionParticipant p) {
            return new Participant(p.getUserId(), p.getDisplayName(), p.getRole(), p.getCategory(),
                    ParticipantState.of(p));
        }
    }

    public record Page(List<SessionSummary> items, String nextCursor, boolean hasMore) {
    }
}
