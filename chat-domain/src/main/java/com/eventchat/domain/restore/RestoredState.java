package com.eventchat.domain.restore;

import com.eventchat.domain.session.PresenceStatus;
import com.eventchat.domain.session.SessionStatus;
import com.eventchat.domain.session.TransitionReason;
import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * seq 1..lastSeq를 fold한 세션 상태. 스냅샷에 그대로 저장하고, 해시를 내서 두 복원 경로의 결과를 비교한다.
 * 시각은 모두 이벤트의 server_ts에서 온다(복원 시점의 현재 시각을 쓰지 않는다). 참여자는 joinSeq 순이다.
 * 기준 시각에 따라 달라지는 값(typing 활성 여부, 만료)은 담지 않고 읽는 쪽이 계산한다.
 */
public record RestoredState(SessionStatus status, Instant createdAt, Instant startedAt, Instant endedAt,
        TransitionReason endReason, int messageCount, long lastSeq, Instant lastEventTs,
        List<Participant> participants) {

    /**
     * @param presenceSinceTs 마지막으로 연결 상태가 정해진 이벤트의 시각
     * @param typingTs        마지막으로 저장된 TYPING_STARTED 시각. 멈췄거나 메시지·퇴장·끊김·종단 전이 뒤면 null
     * @param readAt          마지막 READ_CURSOR 시각(메시지를 보내 옮겨진 커서는 그 메시지 시각)
     */
    public record Participant(UUID userId, String displayName, Role role, Category category, long joinSeq,
            Long leftSeq, Instant joinedAt, Instant leftAt, PresenceStatus presence, Instant presenceSinceTs,
            Long typingSeq, Instant typingTs, long readSeq, int readMsgNo, Instant readAt) {

        public boolean left() {
            return leftSeq != null;
        }
    }
}
