package com.eventchat.session;

import com.eventchat.persistence.session.SessionEventRow;
import java.util.List;
import java.util.UUID;

/**
 * 트랜잭션 안에서 저장(또는 멱등 재전송으로 다시 확인)한 이벤트. 커밋된 뒤에 실시간 채널로 내보낸다.
 */
public record SessionEventsCommitted(UUID sessionId, List<SessionEventRow> events) {
}
