package com.eventchat.session;

import com.eventchat.domain.session.EventType;
import com.eventchat.persistence.session.SessionEventRow;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * 이벤트 응답(envelope). 수집 응답·보충·관리자 조회가 같은 형식을 쓰고, null 필드는 생략한다.
 * 시각은 UTC 밀리초로 내보낸다. clientTime·schemaVersion은 관리자 원본 조회에만 싣는다.
 *
 * @param ttlMs     TYPING_STARTED만: 받은 시각 + ttlMs가 지나면 입력 표시를 끈다
 * @param expiresAt TYPING_STARTED만: 서버 시각 기준 만료 시각(참고용)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventView(long seq, UUID eventId, UUID sessionId, EventType type, UUID actorId, UUID subjectId,
        String clientEventId, Instant serverTime, Integer msgNo, Map<String, Object> payload, Long ttlMs,
        Instant expiresAt, Instant clientTime, Integer schemaVersion) {

    public static EventView of(SessionEventRow row, Duration typingTtl) {
        return of(row, typingTtl, false);
    }

    static EventView of(SessionEventRow row, Duration typingTtl, boolean raw) {
        boolean typing = row.type() == EventType.TYPING_STARTED;
        Instant serverTime = millis(row.serverTs());
        return new EventView(row.seq(), row.eventId(), row.sessionId(), row.type(), row.actorId(), row.subjectId(),
                row.clientEventId(), serverTime, row.msgNo(), payload(row),
                typing ? typingTtl.toMillis() : null,
                typing ? serverTime.plus(typingTtl) : null,
                raw ? millis(row.clientTs()) : null,
                raw ? (int) row.schemaVersion() : null);
    }

    /** 메시지 본문은 따로 저장하지만 응답에서는 payload.content로 합친다. */
    private static Map<String, Object> payload(SessionEventRow row) {
        if (row.type() == EventType.MESSAGE) {
            return Map.of("content", row.content());
        }
        return row.payload();
    }

    private static Instant millis(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }
}
