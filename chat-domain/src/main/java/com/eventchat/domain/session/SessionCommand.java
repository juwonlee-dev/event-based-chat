package com.eventchat.domain.session;

import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public sealed interface SessionCommand {

    /** @param presenceBaseline 참여 직전에 읽은 참여자별 연결 상태. 진행 중 전이 이벤트에 함께 기록한다 */
    record Join(UUID actorId, Role role, Category category, String displayName,
            Map<UUID, PresenceSnapshot> presenceBaseline) implements SessionCommand {
    }

    record Leave(UUID actorId) implements SessionCommand {
    }

    record End(UUID actorId) implements SessionCommand {
    }

    record Message(UUID actorId, String content) implements SessionCommand {
    }

    /** @param at 잠금을 얻은 뒤 정한 이벤트 시각. 저장 여부를 이 시각으로 판정한다 */
    record Typing(UUID actorId, boolean started, Instant at) implements SessionCommand {
    }

    record ReadCursor(UUID actorId, long upToSeq, Instant at) implements SessionCommand {
    }

    /**
     * 서버가 판정한 연결 상태 전이. 클라이언트는 보낼 수 없다.
     *
     * @param ver    Redis가 붙인 사용자별 단조 증가 버전. 이미 반영한 버전 이하면 늦게 온 신호로 보고 버린다
     * @param detail 표시용 payload(DISCONNECT: reason·lastSeenAt, RECONNECT: offlineSince·offlineMs)
     */
    record Presence(UUID userId, PresenceStatus status, long ver, Map<String, Object> detail)
            implements SessionCommand {
    }
}
