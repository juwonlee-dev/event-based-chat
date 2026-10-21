package com.eventchat.stomp;

import com.eventchat.session.EventIngestor.Ingested;
import com.eventchat.session.SessionWriter.IngestResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP SEND 결과 회신(/user/queue/receipts, 보낸 연결에만).
 * MESSAGE는 항상 회신하고, typing·읽음 커서는 거부될 때만 보낸다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventResult(String kind, UUID sessionId, String clientEventId, Status status, Long seq,
        Instant serverTime, Map<String, Object> error) {

    public enum Status {
        ACCEPTED,
        DUPLICATE,
        REJECTED
    }

    static EventResult of(UUID sessionId, String clientEventId, Ingested ingested) {
        Status status = ingested.status() == IngestResult.Status.DUPLICATE ? Status.DUPLICATE : Status.ACCEPTED;
        return new EventResult("EVENT_RESULT", sessionId, clientEventId, status,
                ingested.event() == null ? null : ingested.event().seq(),
                ingested.event() == null ? null : ingested.event().serverTime(), null);
    }

    static EventResult rejected(UUID sessionId, String clientEventId, Map<String, Object> error) {
        return new EventResult("EVENT_RESULT", sessionId, clientEventId, Status.REJECTED, null, null, error);
    }
}
