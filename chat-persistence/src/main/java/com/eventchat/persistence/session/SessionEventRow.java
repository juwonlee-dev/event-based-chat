package com.eventchat.persistence.session;

import com.eventchat.domain.session.EventType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 이벤트 조회 결과. 내부용 컬럼(request_hash)은 읽지 않는다.
 */
public record SessionEventRow(UUID sessionId, long seq, UUID eventId, EventType type, UUID actorId,
        UUID subjectId, String clientEventId, Map<String, Object> payload, String content, Integer msgNo,
        Instant serverTs, Instant clientTs, short schemaVersion) {

    public static SessionEventRow of(SessionEvent event) {
        return new SessionEventRow(event.getSessionId(), event.getSeq(), event.getEventId(), event.getType(),
                event.getActorId(), event.getSubjectId(), event.getClientEventId(), event.getPayload(),
                event.getContent(), event.getMsgNo(), event.getServerTs(), event.getClientTs(),
                event.getSchemaVersion());
    }
}
