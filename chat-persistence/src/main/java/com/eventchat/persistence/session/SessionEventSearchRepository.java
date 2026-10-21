package com.eventchat.persistence.session;

import com.eventchat.domain.restore.ReplayEvent;
import com.eventchat.domain.session.EventType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface SessionEventSearchRepository {

    /** seq 오름차순으로 afterSeq 다음부터 최대 limit건. 나머지 조건은 null이거나 비어 있으면 적용하지 않는다. */
    List<SessionEventRow> findRange(Range range, int limit);

    /** 상태 fold용(Q4): (afterSeq, upToSeq] 구간을 seq 오름차순으로 최대 limit건. 본문은 읽지 않는다. */
    List<ReplayEvent> findForReplay(UUID sessionId, long afterSeq, long upToSeq, int limit);

    /** 시점 → seq(Q3): server_ts가 at 이하인 마지막 이벤트의 seq. */
    Optional<Long> findSeqAt(UUID sessionId, Instant at);

    Optional<Instant> findServerTs(UUID sessionId, long seq);

    /** timeline 화면 항목(Q5): seq가 upToSeq 이하이고 beforeSeq 미만인 표시 이벤트를 seq 내림차순으로 최대 limit건. */
    List<SessionEventRow> findDisplayItems(UUID sessionId, long upToSeq, Long beforeSeq, int limit);

    /**
     * @param from   server_ts 하한(포함)
     * @param before server_ts 상한(미포함)
     */
    record Range(UUID sessionId, long afterSeq, Instant from, Instant before, Set<EventType> types) {
    }
}
