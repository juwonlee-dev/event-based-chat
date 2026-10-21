package com.eventchat.domain.restore;

import com.eventchat.domain.session.EventType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 상태 fold에 필요한 이벤트 필드. 메시지 본문은 상태에 영향이 없으므로 읽지 않는다.
 */
public record ReplayEvent(long seq, EventType type, UUID actorId, UUID subjectId, Map<String, Object> payload,
        Integer msgNo, Instant serverTs, short schemaVersion) {
}
