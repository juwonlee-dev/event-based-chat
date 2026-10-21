package com.eventchat.restore;

import com.eventchat.domain.session.PresenceStatus;
import com.eventchat.domain.session.SessionStatus;
import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
import com.eventchat.session.EventView;
import com.eventchat.session.SessionView.ParticipantState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * timeline 응답. participants·asOf는 기준 시점(seq S, 기준 시각 T)의 상태이고, session은 지금의 세션
 * (시간 슬라이더 범위 createdAt ~ endedAt ?? lastEventAt을 잡는 용도)이다.
 */
public record TimelineView(SessionInfo session, AsOf asOf, RestoredFrom restoredFrom,
        List<ParticipantView> participants, Items items) {

    public record SessionInfo(UUID id, String title, Category category, SessionStatus status, Instant createdAt,
            Instant startedAt, Instant endedAt, long lastSeq, Instant lastEventAt) {
    }

    /**
     * @param requestedAt   요청한 at(없으면 null)
     * @param referenceTime typing·presence를 판정한 기준 시각 T
     * @param status        S 시점의 세션 상태
     */
    public record AsOf(Instant requestedAt, long seq, Instant referenceTime, SessionStatus status,
            int messageCount) {
    }

    /** @param snapshotSeq 스냅샷에서 시작했으면 그 seq, 처음부터 fold했으면 null */
    public record RestoredFrom(Mode mode, Long snapshotSeq, long replayedEvents, String stateHash) {
    }

    public enum Mode {
        SNAPSHOT_INCREMENTAL,
        FULL_REPLAY
    }

    public record ParticipantView(UUID userId, String displayName, Role role, Category category,
            ParticipantState state, Instant joinedAt, Instant leftAt, Presence presence, Typing typing,
            ReadCursor readCursor, int unreadCount) {
    }

    public record Presence(PresenceStatus status, Instant since) {
    }

    public record Typing(boolean active, Instant expiresAt) {
    }

    public record ReadCursor(long seq, Instant at) {
    }

    /** @param nextBeforeSeq 다음(이전) 페이지를 받을 beforeSeq. 이 페이지의 최소 seq다 */
    public record Items(List<EventView> list, boolean hasMore, Long nextBeforeSeq) {
    }
}
