package com.eventchat.persistence.session;

import static com.eventchat.persistence.session.QSessionEvent.sessionEvent;

import com.eventchat.domain.restore.ReplayEvent;
import com.eventchat.domain.session.EventType;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class SessionEventSearchRepositoryImpl implements SessionEventSearchRepository {

    /** 화면에 보이는 타입. 부분 인덱스 session_event_display_idx의 조건과 같아야 그 인덱스를 탄다. */
    private static final Set<EventType> DISPLAY_TYPES = EnumSet.of(EventType.SESSION_CREATED, EventType.JOIN,
            EventType.LEAVE, EventType.SESSION_STATUS_CHANGED, EventType.MESSAGE, EventType.DISCONNECT,
            EventType.RECONNECT);

    private final JPAQueryFactory queryFactory;

    @Override
    public List<SessionEventRow> findRange(Range range, int limit) {
        BooleanBuilder where = new BooleanBuilder()
                .and(sessionEvent.sessionId.eq(range.sessionId()))
                .and(sessionEvent.seq.gt(range.afterSeq()));
        if (range.from() != null) {
            where.and(sessionEvent.serverTs.goe(range.from()));
        }
        if (range.before() != null) {
            where.and(sessionEvent.serverTs.lt(range.before()));
        }
        if (range.types() != null && !range.types().isEmpty()) {
            where.and(sessionEvent.type.in(range.types()));
        }
        return queryFactory
                .select(row())
                .from(sessionEvent)
                .where(where)
                .orderBy(sessionEvent.seq.asc())
                .limit(limit)
                .fetch();
    }

    @Override
    public List<ReplayEvent> findForReplay(UUID sessionId, long afterSeq, long upToSeq, int limit) {
        return queryFactory
                .select(Projections.constructor(ReplayEvent.class,
                        sessionEvent.seq, sessionEvent.type, sessionEvent.actorId, sessionEvent.subjectId,
                        sessionEvent.payload, sessionEvent.msgNo, sessionEvent.serverTs, sessionEvent.schemaVersion))
                .from(sessionEvent)
                .where(sessionEvent.sessionId.eq(sessionId), sessionEvent.seq.gt(afterSeq),
                        sessionEvent.seq.loe(upToSeq))
                .orderBy(sessionEvent.seq.asc())
                .limit(limit)
                .fetch();
    }

    @Override
    public Optional<Long> findSeqAt(UUID sessionId, Instant at) {
        return Optional.ofNullable(queryFactory
                .select(sessionEvent.seq)
                .from(sessionEvent)
                .where(sessionEvent.sessionId.eq(sessionId), sessionEvent.serverTs.loe(at))
                .orderBy(sessionEvent.serverTs.desc(), sessionEvent.seq.desc())
                .fetchFirst());
    }

    @Override
    public Optional<Instant> findServerTs(UUID sessionId, long seq) {
        return Optional.ofNullable(queryFactory
                .select(sessionEvent.serverTs)
                .from(sessionEvent)
                .where(sessionEvent.sessionId.eq(sessionId), sessionEvent.seq.eq(seq))
                .fetchFirst());
    }

    @Override
    public List<SessionEventRow> findDisplayItems(UUID sessionId, long upToSeq, Long beforeSeq, int limit) {
        BooleanBuilder where = new BooleanBuilder()
                .and(sessionEvent.sessionId.eq(sessionId))
                .and(sessionEvent.type.in(DISPLAY_TYPES))
                .and(sessionEvent.seq.loe(upToSeq));
        if (beforeSeq != null) {
            where.and(sessionEvent.seq.lt(beforeSeq));
        }
        return queryFactory
                .select(row())
                .from(sessionEvent)
                .where(where)
                .orderBy(sessionEvent.seq.desc())
                .limit(limit)
                .fetch();
    }

    private static ConstructorExpression<SessionEventRow> row() {
        return Projections.constructor(SessionEventRow.class,
                sessionEvent.sessionId, sessionEvent.seq, sessionEvent.eventId, sessionEvent.type,
                sessionEvent.actorId, sessionEvent.subjectId, sessionEvent.clientEventId, sessionEvent.payload,
                sessionEvent.content, sessionEvent.msgNo, sessionEvent.serverTs, sessionEvent.clientTs,
                sessionEvent.schemaVersion);
    }
}
