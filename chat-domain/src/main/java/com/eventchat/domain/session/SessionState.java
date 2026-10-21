package com.eventchat.domain.session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 판정에 필요한 만큼만 담은 세션의 현재 상태.
 */
public record SessionState(SessionStatus status, long lastSeq, List<Participant> participants) {

    public Optional<Participant> participant(UUID userId) {
        return participants.stream().filter(p -> p.userId().equals(userId)).findFirst();
    }

    /**
     * @param typingAt        마지막으로 저장한 TYPING_STARTED 시각. 멈췄거나 메시지를 보냈으면 null
     * @param readSeq         저장된 읽음 커서
     * @param lastReadStoreAt 마지막으로 READ_CURSOR를 저장한 시각
     * @param presence        마지막으로 반영한 연결 상태와 그 버전
     */
    public record Participant(UUID userId, boolean left, Instant typingAt, long readSeq, Instant lastReadStoreAt,
            PresenceStatus presence, long presenceVer) {
    }
}
