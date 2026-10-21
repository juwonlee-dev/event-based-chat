package com.eventchat.persistence.session;

import static com.eventchat.persistence.session.QSessionEvent.sessionEvent;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class SessionEventSearchRepositoryImpl implements SessionEventSearchRepository {

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
                .select(Projections.constructor(SessionEventRow.class,
                        sessionEvent.sessionId, sessionEvent.seq, sessionEvent.eventId, sessionEvent.type,
                        sessionEvent.actorId, sessionEvent.subjectId, sessionEvent.clientEventId, sessionEvent.payload,
                        sessionEvent.content, sessionEvent.msgNo, sessionEvent.serverTs, sessionEvent.clientTs,
                        sessionEvent.schemaVersion))
                .from(sessionEvent)
                .where(where)
                .orderBy(sessionEvent.seq.asc())
                .limit(limit)
                .fetch();
    }
}
