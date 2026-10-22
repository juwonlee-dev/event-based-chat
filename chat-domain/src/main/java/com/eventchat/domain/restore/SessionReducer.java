package com.eventchat.domain.restore;

import com.eventchat.domain.restore.RestoredState.Participant;
import com.eventchat.domain.session.PresenceStatus;
import com.eventchat.domain.session.SessionStatus;
import com.eventchat.domain.session.TransitionReason;
import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * 세션 이벤트를 seq 순으로 fold하는 유일한 리듀서. 스냅샷 생성과 timeline 복원이 같은 코드를 쓴다.
 * <p>
 * 중복·순서 문제는 저장 단계에서 이미 걸러졌으므로 여기서는 fold만 한다. 대신 seq 빈틈, 모르는 타입,
 * 모르는 스키마 버전을 만나면 건너뛰지 않고 실패한다(건너뛰면 조용히 틀린 상태가 나온다).
 * 리듀서 규칙을 바꾸면 {@link #VERSION}을 올린다. 버전이 다른 스냅샷은 쓰지 않고 처음부터 다시 fold한다.
 */
public final class SessionReducer {

    public static final short VERSION = 1;
    private static final short SCHEMA_VERSION = 1;

    private SessionStatus status;
    private Instant createdAt;
    private Instant startedAt;
    private Instant endedAt;
    private TransitionReason endReason;
    private int messageCount;
    private long lastSeq;
    private Instant lastEventTs;
    private final List<Participant> participants = new ArrayList<>();

    private SessionReducer() {
    }

    public static SessionReducer empty() {
        return new SessionReducer();
    }

    public static SessionReducer from(RestoredState state) {
        SessionReducer reducer = new SessionReducer();
        reducer.status = state.status();
        reducer.createdAt = state.createdAt();
        reducer.startedAt = state.startedAt();
        reducer.endedAt = state.endedAt();
        reducer.endReason = state.endReason();
        reducer.messageCount = state.messageCount();
        reducer.lastSeq = state.lastSeq();
        reducer.lastEventTs = state.lastEventTs();
        reducer.participants.addAll(state.participants());
        return reducer;
    }

    public long lastSeq() {
        return lastSeq;
    }

    public RestoredState state() {
        return new RestoredState(status, createdAt, startedAt, endedAt, endReason, messageCount, lastSeq,
                lastEventTs, List.copyOf(participants));
    }

    public void apply(ReplayEvent event) {
        if (event.seq() != lastSeq + 1) {
            throw new ReplayUnsupportedException(event.seq(), "seq가 이어지지 않습니다: " + lastSeq + " 다음 " + event.seq());
        }
        if (event.schemaVersion() != SCHEMA_VERSION) {
            throw new ReplayUnsupportedException(event.seq(), "지원하지 않는 스키마 버전입니다: " + event.schemaVersion());
        }
        try {
            fold(event);
        } catch (ReplayUnsupportedException e) {
            throw e;
        } catch (RuntimeException e) {
            // payload 결함(필드 누락, 형식 오류)도 건너뛰지 않고 결정적으로 실패시킨다.
            throw new ReplayUnsupportedException(event.seq(), "이벤트를 해석할 수 없습니다: " + e);
        }
        lastSeq = event.seq();
        lastEventTs = event.serverTs();
    }

    private void fold(ReplayEvent event) {
        Instant ts = event.serverTs();
        switch (event.type()) {
            case SESSION_CREATED -> {
                status = SessionStatus.WAITING;
                createdAt = ts;
            }
            case JOIN -> {
                Map<String, Object> payload = event.payload();
                participants.add(new Participant(event.subjectId(), (String) payload.get("displayName"),
                        Role.valueOf((String) payload.get("role")), Category.valueOf((String) payload.get("category")),
                        event.seq(), null, ts, null, PresenceStatus.OFFLINE, ts, null, null, 0, 0, null));
            }
            case LEAVE -> update(event, p -> new Participant(p.userId(), p.displayName(), p.role(), p.category(),
                    p.joinSeq(), event.seq(), p.joinedAt(), ts, p.presence(), p.presenceSinceTs(), null, null,
                    p.readSeq(), p.readMsgNo(), p.readAt()));
            case SESSION_STATUS_CHANGED -> statusChanged(event);
            case MESSAGE -> {
                messageCount++;
                // 보낸 사람은 자기 메시지까지 읽은 것으로 보고, 입력도 멈춘 것으로 본다.
                int msgNo = event.msgNo();
                updateUser(event.actorId(), event, p -> withTyping(withRead(p, event.seq(), msgNo, ts), null, null));
            }
            case TYPING_STARTED -> update(event, p -> withTyping(p, event.seq(), ts));
            case TYPING_STOPPED -> update(event, p -> withTyping(p, null, null));
            case READ_CURSOR -> update(event, p -> withRead(p, number(event, "upToSeq").longValue(),
                    number(event, "readMsgNo").intValue(), ts));
            case DISCONNECT -> update(event, p -> withTyping(withPresence(p, PresenceStatus.OFFLINE, ts), null, null));
            case RECONNECT -> update(event, p -> withPresence(p, PresenceStatus.ONLINE, ts));
            default -> throw new ReplayUnsupportedException(event.seq(), "지원하지 않는 이벤트 타입입니다: " + event.type());
        }
    }

    /** 진행 중이 될 때 두 사람의 연결 상태 기준값을 싣고, 종단 전이는 모두의 입력을 끈다. */
    @SuppressWarnings("unchecked")
    private void statusChanged(ReplayEvent event) {
        Instant ts = event.serverTs();
        SessionStatus to = SessionStatus.valueOf((String) event.payload().get("to"));
        status = to;
        if (to == SessionStatus.IN_PROGRESS) {
            startedAt = ts;
            Object baseline = event.payload().get("presenceBaseline");
            if (baseline instanceof Map<?, ?> byUser) {
                ((Map<String, Map<String, Object>>) byUser).forEach((userId, snapshot) ->
                        updateUser(UUID.fromString(userId), event, p -> withPresence(p,
                                PresenceStatus.valueOf((String) snapshot.get("state")), ts)));
            }
        }
        if (to.isTerminal()) {
            endedAt = ts;
            endReason = TransitionReason.valueOf((String) event.payload().get("reason"));
            participants.replaceAll(p -> withTyping(p, null, null));
        }
    }

    private void update(ReplayEvent event, UnaryOperator<Participant> change) {
        updateUser(event.subjectId(), event, change);
    }

    private void updateUser(UUID userId, ReplayEvent event, UnaryOperator<Participant> change) {
        for (int i = 0; i < participants.size(); i++) {
            if (participants.get(i).userId().equals(userId)) {
                participants.set(i, change.apply(participants.get(i)));
                return;
            }
        }
        throw new ReplayUnsupportedException(event.seq(), "참여하지 않은 사용자의 이벤트입니다: " + userId);
    }

    private static Number number(ReplayEvent event, String field) {
        if (!(event.payload().get(field) instanceof Number value)) {
            throw new ReplayUnsupportedException(event.seq(), "payload." + field + "가 없습니다.");
        }
        return value;
    }

    private static Participant withTyping(Participant p, Long typingSeq, Instant typingTs) {
        return new Participant(p.userId(), p.displayName(), p.role(), p.category(), p.joinSeq(), p.leftSeq(),
                p.joinedAt(), p.leftAt(), p.presence(), p.presenceSinceTs(), typingSeq, typingTs, p.readSeq(),
                p.readMsgNo(), p.readAt());
    }

    private static Participant withRead(Participant p, long readSeq, int readMsgNo, Instant readAt) {
        return new Participant(p.userId(), p.displayName(), p.role(), p.category(), p.joinSeq(), p.leftSeq(),
                p.joinedAt(), p.leftAt(), p.presence(), p.presenceSinceTs(), p.typingSeq(), p.typingTs(), readSeq,
                readMsgNo, readAt);
    }

    private static Participant withPresence(Participant p, PresenceStatus presence, Instant since) {
        return new Participant(p.userId(), p.displayName(), p.role(), p.category(), p.joinSeq(), p.leftSeq(),
                p.joinedAt(), p.leftAt(), presence, since, p.typingSeq(), p.typingTs(), p.readSeq(), p.readMsgNo(),
                p.readAt());
    }
}
