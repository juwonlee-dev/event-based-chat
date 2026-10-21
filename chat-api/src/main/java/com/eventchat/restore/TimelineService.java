package com.eventchat.restore;

import com.eventchat.domain.restore.RestoredState;
import com.eventchat.domain.restore.SessionReducer;
import com.eventchat.persistence.session.ChatSession;
import com.eventchat.persistence.session.ChatSessionRepository;
import com.eventchat.persistence.session.SessionEventRepository;
import com.eventchat.persistence.session.SessionEventRow;
import com.eventchat.persistence.session.SessionParticipantRepository;
import com.eventchat.persistence.session.SessionSnapshot;
import com.eventchat.persistence.session.SessionSnapshotRepository;
import com.eventchat.session.EventView;
import com.eventchat.session.IngestProperties;
import com.eventchat.session.SessionErrors;
import com.eventchat.session.SessionQueryService;
import com.eventchat.session.SessionView.ParticipantState;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지정한 시점의 세션 상태 복원(읽기 전용).
 * <pre>
 * S = atSeq ?? seq(at) ?? last_seq            T = at ?? ts(S)(atSeq) ?? DB 현재 시각(현재)
 * 스냅샷.last_seq ≤ S이고 리듀서 버전이 같으면  스냅샷 + (스냅샷, S] fold   SNAPSHOT_INCREMENTAL
 * 그 밖에는                                   1..S fold                   FULL_REPLAY
 * </pre>
 * 상태는 S까지의 이벤트만으로, 시간 판정(typing 만료)은 이벤트 시각과 T만으로 계산한다.
 */
@Service
public class TimelineService {

    private final ChatSessionRepository sessions;
    private final SessionParticipantRepository participants;
    private final SessionEventRepository events;
    private final SessionSnapshotRepository snapshots;
    private final StateFolder folder;
    private final RestoreProperties properties;
    private final Duration typingTtl;

    TimelineService(ChatSessionRepository sessions, SessionParticipantRepository participants,
            SessionEventRepository events, SessionSnapshotRepository snapshots, StateFolder folder,
            RestoreProperties properties, IngestProperties ingestProperties) {
        this.sessions = sessions;
        this.participants = participants;
        this.events = events;
        this.snapshots = snapshots;
        this.folder = folder;
        this.properties = properties;
        this.typingTtl = ingestProperties.typingTtl();
    }

    /**
     * @param at     기준 시각(atSeq와 함께 줄 수 없다)
     * @param atSeq  기준 seq
     * @param before 화면 항목 이전 페이지(이 seq 미만)
     */
    @Transactional(readOnly = true)
    public TimelineView timeline(UUID sessionId, UUID viewerId, boolean admin, Instant at, Long atSeq, Long before,
            int itemLimit, boolean ignoreSnapshot) {
        long deadline = System.nanoTime() + properties.timeout().toNanos();
        ChatSession session = sessions.findById(sessionId).orElseThrow(SessionErrors::notFound);
        // 퇴장한 참여자는 과거에 참여했던 시점이라도 볼 수 없다. 대기 중인 세션도 공개하지 않는다.
        SessionQueryService.checkReadable(session, participants.findBySessionIdOrderByJoinSeq(sessionId), viewerId,
                admin, false);
        try {
            return restore(session, at, atSeq, before, itemLimit, ignoreSnapshot, deadline);
        } catch (QueryTimeoutException e) {
            throw new ApiException(ErrorCode.RESTORE_TIMEOUT, "복원 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private TimelineView restore(ChatSession session, Instant at, Long atSeq, Long before, int itemLimit,
            boolean ignoreSnapshot, long deadline) {
        UUID sessionId = session.getId();
        Instant requestedAt = at;
        long seq;
        Instant referenceTime;
        if (at != null) {
            Instant dbNow = sessions.clockTimestamp();
            if (at.isAfter(dbNow.plus(properties.futureTolerance()))) {
                throw new ApiException(ErrorCode.AT_IN_FUTURE, "at이 현재보다 " + properties.futureTolerance().toSeconds()
                        + "초 넘게 미래입니다.");
            }
            referenceTime = at.isAfter(dbNow) ? dbNow : at;
            // 응답 시각이 밀리초라서, 받은 시각을 그대로 넣어도 그 이벤트가 포함되도록 그 밀리초 끝까지 본다.
            Instant upTo = referenceTime.truncatedTo(ChronoUnit.MILLIS).plusNanos(999_000);
            if (upTo.isBefore(session.getCreatedAt())) {
                throw beforeStart();
            }
            seq = events.findSeqAt(sessionId, upTo).orElseThrow(TimelineService::beforeStart);
        } else if (atSeq != null) {
            seq = Math.min(atSeq, session.getLastSeq());
            referenceTime = events.findServerTs(sessionId, seq).orElseThrow();
        } else {
            seq = session.getLastSeq();
            referenceTime = sessions.clockTimestamp();
        }

        Optional<SessionSnapshot> snapshot = ignoreSnapshot ? Optional.empty() : snapshots.findById(sessionId)
                .filter(s -> s.getReducerVersion() == SessionReducer.VERSION && s.getLastSeq() <= seq);
        SessionReducer reducer = snapshot.map(s -> SessionReducer.from(folder.read(s.getState())))
                .orElseGet(SessionReducer::empty);
        long replayed = folder.fold(sessionId, reducer, seq, deadline);
        RestoredState state = reducer.state();

        TimelineView.RestoredFrom restoredFrom = new TimelineView.RestoredFrom(
                snapshot.isPresent() ? TimelineView.Mode.SNAPSHOT_INCREMENTAL : TimelineView.Mode.FULL_REPLAY,
                snapshot.map(SessionSnapshot::getLastSeq).orElse(null), replayed, folder.hash(state));
        return new TimelineView(sessionInfo(session),
                new TimelineView.AsOf(millis(requestedAt), seq, millis(referenceTime), state.status(),
                        state.messageCount()),
                restoredFrom,
                state.participants().stream().map(p -> participant(state, p, referenceTime)).toList(),
                items(sessionId, seq, before, itemLimit));
    }

    /** 화면 항목은 최근 것부터 끊어 읽고, 한 페이지 안에서는 seq 오름차순으로 돌려준다. */
    private TimelineView.Items items(UUID sessionId, long seq, Long before, int itemLimit) {
        List<SessionEventRow> rows = new ArrayList<>(events.findDisplayItems(sessionId, seq, before, itemLimit + 1));
        boolean hasMore = rows.size() > itemLimit;
        if (hasMore) {
            rows.removeLast();
        }
        Collections.reverse(rows);
        List<EventView> list = rows.stream().map(row -> EventView.of(row, typingTtl)).toList();
        return new TimelineView.Items(list, hasMore, list.isEmpty() ? null : list.getFirst().seq());
    }

    /** 입력 중 = 마지막 typing이 STARTED이고 T가 그 시각 + TTL 전이며, 그 뒤에 입력을 끄는 이벤트가 없음. */
    private TimelineView.ParticipantView participant(RestoredState state, RestoredState.Participant p,
            Instant referenceTime) {
        boolean typing = p.typingTs() != null && referenceTime.isBefore(p.typingTs().plus(typingTtl));
        return new TimelineView.ParticipantView(p.userId(), p.displayName(), p.role(), p.category(),
                p.left() ? ParticipantState.LEFT : ParticipantState.JOINED, millis(p.joinedAt()), millis(p.leftAt()),
                new TimelineView.Presence(p.presence(), millis(p.presenceSinceTs())),
                new TimelineView.Typing(typing, typing ? millis(p.typingTs().plus(typingTtl)) : null),
                new TimelineView.ReadCursor(p.readSeq(), millis(p.readAt())),
                Math.max(0, state.messageCount() - p.readMsgNo()));
    }

    private static TimelineView.SessionInfo sessionInfo(ChatSession session) {
        return new TimelineView.SessionInfo(session.getId(), session.getTitle(), session.getCategory(),
                session.getStatus(), millis(session.getCreatedAt()), millis(session.getStartedAt()),
                millis(session.getEndedAt()), session.getLastSeq(), millis(session.getLastEventAt()));
    }

    private static ApiException beforeStart() {
        return new ApiException(ErrorCode.BEFORE_SESSION_START, "세션이 만들어지기 전 시각입니다.");
    }

    private static Instant millis(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }
}
