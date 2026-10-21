package com.eventchat.persistence.session;

import com.eventchat.domain.session.EventType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SessionEventSearchRepository {

    /** seq 오름차순으로 afterSeq 다음부터 최대 limit건. 나머지 조건은 null이거나 비어 있으면 적용하지 않는다. */
    List<SessionEventRow> findRange(Range range, int limit);

    /**
     * @param from   server_ts 하한(포함)
     * @param before server_ts 상한(미포함)
     */
    record Range(UUID sessionId, long afterSeq, Instant from, Instant before, Set<EventType> types) {
    }
}
