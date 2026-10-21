package com.eventchat.persistence.session;

import static com.eventchat.persistence.session.QChatSession.chatSession;
import static com.eventchat.persistence.session.QSessionParticipant.sessionParticipant;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class ChatSessionSearchRepositoryImpl implements ChatSessionSearchRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<SessionSummaryRow> search(SessionSearch condition, int limit) {
        return queryFactory
                .select(Projections.constructor(SessionSummaryRow.class,
                        chatSession.id, chatSession.title, chatSession.category, chatSession.status,
                        chatSession.createdBy, chatSession.createdAt, chatSession.startedAt, chatSession.endedAt,
                        chatSession.endReason, chatSession.lastEventAt, chatSession.messageCount))
                .from(chatSession)
                .where(where(condition))
                .orderBy(chatSession.createdAt.desc(), chatSession.id.desc())
                .limit(limit)
                .fetch();
    }

    private static BooleanBuilder where(SessionSearch condition) {
        BooleanBuilder where = new BooleanBuilder();
        if (condition.createdFrom() != null) {
            where.and(chatSession.createdAt.goe(condition.createdFrom()));
        }
        if (condition.createdBefore() != null) {
            where.and(chatSession.createdAt.lt(condition.createdBefore()));
        }
        if (condition.statuses() != null && !condition.statuses().isEmpty()) {
            where.and(chatSession.status.in(condition.statuses()));
        }
        if (condition.categories() != null && !condition.categories().isEmpty()) {
            where.and(chatSession.category.in(condition.categories()));
        }
        if (condition.title() != null) {
            // lower(title) like '%q%' → lower(title) trigram 인덱스
            where.and(chatSession.title.containsIgnoreCase(condition.title()));
        }
        if (condition.participantId() != null) {
            where.and(JPAExpressions.selectOne()
                    .from(sessionParticipant)
                    .where(sessionParticipant.sessionId.eq(chatSession.id),
                            sessionParticipant.userId.eq(condition.participantId()),
                            sessionParticipant.leftSeq.isNull())
                    .exists());
        }
        if (condition.after() != null) {
            SessionSearch.Position after = condition.after();
            where.and(chatSession.createdAt.lt(after.createdAt())
                    .or(chatSession.createdAt.eq(after.createdAt()).and(chatSession.id.lt(after.id()))));
        }
        return where;
    }
}
