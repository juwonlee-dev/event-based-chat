package com.eventchat.session;

import com.eventchat.domain.session.Rejection;
import com.eventchat.domain.session.SessionStatus;
import com.eventchat.persistence.session.ChatSession;
import com.eventchat.persistence.session.ChatSessionRepository;
import com.eventchat.persistence.session.SessionParticipant;
import com.eventchat.persistence.session.SessionParticipantRepository;
import com.eventchat.persistence.session.SessionSearch;
import com.eventchat.persistence.session.SessionSummaryRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionQueryService {

    /** 목록 날짜 필터를 해석하는 시간대. 응답 시각은 모두 UTC다. */
    private static final ZoneId LIST_ZONE = ZoneId.of("Asia/Seoul");

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

    /** 세션을 키셋으로 한 페이지 읽고, 참여자는 그 페이지의 세션들에 대해 한 번에 읽는다. */
    public SessionSummary.Page list(SessionListQuery query, UUID viewerId, String cursor, int limit) {
        String fingerprint = query.fingerprint();
        SessionSearch search = new SessionSearch(
                startOfDay(query.createdFrom()),
                query.createdTo() == null ? null : startOfDay(query.createdTo().plusDays(1)),
                query.statuses(), query.categories(), query.title(),
                query.onlyMine() ? viewerId : null,
                cursor == null ? null : SessionCursors.decode(cursor, fingerprint));

        List<SessionSummaryRow> rows = sessions.search(search, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<SessionSummaryRow> page = hasMore ? rows.subList(0, limit) : rows;
        Map<UUID, List<SessionParticipant>> participantsBySession = page.isEmpty()
                ? Map.of()
                : participants.findBySessionIdInOrderByJoinSeq(page.stream().map(SessionSummaryRow::id).toList())
                        .stream()
                        .collect(Collectors.groupingBy(SessionParticipant::getSessionId));

        List<SessionSummary> items = page.stream()
                .map(row -> SessionSummary.of(row, participantsBySession.getOrDefault(row.id(), List.of())))
                .toList();
        String nextCursor = null;
        if (hasMore) {
            SessionSummaryRow last = page.getLast();
            nextCursor = SessionCursors.encode(new SessionSearch.Position(last.createdAt(), last.id()), fingerprint);
        }
        return new SessionSummary.Page(items, nextCursor, hasMore);
    }

    private static Instant startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(LIST_ZONE).toInstant();
    }
}
