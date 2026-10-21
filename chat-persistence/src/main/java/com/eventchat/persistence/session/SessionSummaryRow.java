package com.eventchat.persistence.session;

import com.eventchat.domain.session.SessionStatus;
import com.eventchat.domain.session.TransitionReason;
import com.eventchat.domain.user.Category;
import java.time.Instant;
import java.util.UUID;

/**
 * 목록 한 행. 엔티티를 거치지 않고 필요한 컬럼만 읽는다.
 */
public record SessionSummaryRow(UUID id, String title, Category category, SessionStatus status, UUID createdBy,
        Instant createdAt, Instant startedAt, Instant endedAt, TransitionReason endReason, Instant lastEventAt,
        int messageCount) {
}
