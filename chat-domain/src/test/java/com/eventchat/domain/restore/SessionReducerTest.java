package com.eventchat.domain.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventchat.domain.restore.RestoredState.Participant;
import com.eventchat.domain.session.EventType;
import com.eventchat.domain.session.PresenceStatus;
import com.eventchat.domain.session.SessionStatus;
import com.eventchat.domain.session.TransitionReason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 리듀서가 시점마다 내놓는 상태를 사람이 손으로 계산한 기대값과 맞춰 본다.
 * 같은 리듀서를 두 경로로 돌려 비교하는 것만으로는 규칙 자체가 틀린 것을 잡지 못하므로, 기대값은 이벤트 목록만 보고 따로 적었다.
 */
class SessionReducerTest {

    private static final Instant T0 = Instant.parse("2026-10-22T01:00:00.000001Z");

    private final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static Instant t(int seconds) {
        return T0.plusSeconds(seconds);
    }

    private static ReplayEvent event(long seq, EventType type, UUID actor, UUID subject, Map<String, Object> payload,
            Integer msgNo, Instant at) {
        return new ReplayEvent(seq, type, actor, subject, payload, msgNo, at, (short) 1);
    }

    private static Map<String, Object> joinPayload(String name) {
        return Map.of("role", "USER", "category", "BANK", "displayName", name);
    }

    /** 상담 한 건: 생성 → 참여 → 메시지 → typing → 읽음 → 끊김·재연결 → 퇴장. */
    private List<ReplayEvent> conversation() {
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("from", "WAITING");
        started.put("to", "IN_PROGRESS");
        started.put("reason", "JOINED");
        started.put("presenceBaseline", Map.of(
                a.toString(), Map.of("state", "ONLINE", "ver", 5),
                b.toString(), Map.of("state", "OFFLINE", "ver", 0)));
        return List.of(
                event(1, EventType.SESSION_CREATED, a, null, Map.of("createdBy", a.toString()), null, t(0)),
                event(2, EventType.JOIN, a, a, joinPayload("상담원"), null, t(0)),
                event(3, EventType.JOIN, b, b, joinPayload("고객"), null, t(1)),
                event(4, EventType.SESSION_STATUS_CHANGED, b, null, started, null, t(1)),
                event(5, EventType.MESSAGE, a, null, null, 1, t(2)),
                event(6, EventType.TYPING_STARTED, b, b, Map.of(), null, t(3)),
                event(7, EventType.MESSAGE, b, null, null, 2, t(4)),
                event(8, EventType.READ_CURSOR, a, a, Map.of("upToSeq", 7, "readMsgNo", 2), null, t(5)),
                event(9, EventType.DISCONNECT, null, b, Map.of("reason", "SOCKET_CLOSED"), null, t(6)),
                event(10, EventType.RECONNECT, null, b, Map.of(), null, t(7)),
                event(11, EventType.TYPING_STARTED, a, a, Map.of(), null, t(8)),
                event(12, EventType.LEAVE, b, b, Map.of(), null, t(9)),
                event(13, EventType.SESSION_STATUS_CHANGED, b, null,
                        Map.of("from", "IN_PROGRESS", "to", "SUSPENDED", "reason", "LEFT"), null, t(9)));
    }

    private RestoredState foldUpTo(long seq) {
        SessionReducer reducer = SessionReducer.empty();
        conversation().stream().filter(e -> e.seq() <= seq).forEach(reducer::apply);
        return reducer.state();
    }

    private static Participant participant(RestoredState state, UUID userId) {
        return state.participants().stream().filter(p -> p.userId().equals(userId)).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("시점별 상태(손으로 계산한 기대값)")
    class PointInTime {

        @Test
        @DisplayName("seq 2: 대기 중, 만든 사람만 참여, 메시지 없음")
        void waiting() {
            RestoredState state = foldUpTo(2);
            assertThat(state.status()).isEqualTo(SessionStatus.WAITING);
            assertThat(state.createdAt()).isEqualTo(t(0));
            assertThat(state.participants()).extracting(Participant::userId).containsExactly(a);
            assertThat(state.messageCount()).isZero();
            assertThat(state.lastSeq()).isEqualTo(2);
        }

        @Test
        @DisplayName("seq 4: 진행 중이 되고 presence는 전이 이벤트의 기준값과 시각을 따른다")
        void started() {
            RestoredState state = foldUpTo(4);
            assertThat(state.status()).isEqualTo(SessionStatus.IN_PROGRESS);
            assertThat(state.startedAt()).isEqualTo(t(1));
            assertThat(participant(state, a).presence()).isEqualTo(PresenceStatus.ONLINE);
            assertThat(participant(state, a).presenceSinceTs()).isEqualTo(t(1));
            assertThat(participant(state, b).presence()).isEqualTo(PresenceStatus.OFFLINE);
            assertThat(state.participants()).extracting(Participant::joinSeq).containsExactly(2L, 3L);
        }

        @Test
        @DisplayName("seq 6: B가 입력 중")
        void typing() {
            assertThat(participant(foldUpTo(6), b).typingTs()).isEqualTo(t(3));
            assertThat(participant(foldUpTo(6), b).typingSeq()).isEqualTo(6L);
        }

        @Test
        @DisplayName("seq 7: B가 메시지를 보내면 typing이 꺼지고 자기 메시지까지 읽은 것으로 본다")
        void messageClearsTypingAndReads() {
            RestoredState state = foldUpTo(7);
            Participant participantB = participant(state, b);
            assertThat(participantB.typingTs()).isNull();
            assertThat(participantB.readSeq()).isEqualTo(7);
            assertThat(participantB.readMsgNo()).isEqualTo(2);
            assertThat(state.messageCount()).isEqualTo(2);
            assertThat(participant(state, a).readMsgNo()).isEqualTo(1);
        }

        @Test
        @DisplayName("seq 8: A의 읽음 커서는 payload의 값과 이벤트 시각을 따른다")
        void readCursor() {
            Participant participantA = participant(foldUpTo(8), a);
            assertThat(participantA.readSeq()).isEqualTo(7);
            assertThat(participantA.readMsgNo()).isEqualTo(2);
            assertThat(participantA.readAt()).isEqualTo(t(5));
        }

        @Test
        @DisplayName("seq 9·10: 끊김과 재연결은 이벤트 시각을 since로 쓴다")
        void presence() {
            assertThat(participant(foldUpTo(9), b).presence()).isEqualTo(PresenceStatus.OFFLINE);
            assertThat(participant(foldUpTo(9), b).presenceSinceTs()).isEqualTo(t(6));
            assertThat(participant(foldUpTo(10), b).presence()).isEqualTo(PresenceStatus.ONLINE);
            assertThat(participant(foldUpTo(10), b).presenceSinceTs()).isEqualTo(t(7));
        }

        @Test
        @DisplayName("seq 13: 퇴장으로 중단되고, 종단 전이는 남은 사람의 typing도 끈다")
        void suspended() {
            RestoredState state = foldUpTo(13);
            assertThat(state.status()).isEqualTo(SessionStatus.SUSPENDED);
            assertThat(state.endedAt()).isEqualTo(t(9));
            assertThat(state.endReason()).isEqualTo(TransitionReason.LEFT);
            assertThat(participant(state, b).left()).isTrue();
            assertThat(participant(state, b).leftSeq()).isEqualTo(12L);
            assertThat(participant(foldUpTo(12), a).typingTs()).isEqualTo(t(8));
            assertThat(participant(state, a).typingTs()).isNull();
            assertThat(state.lastEventTs()).isEqualTo(t(9));
        }

        @Test
        @DisplayName("DISCONNECT는 그 사용자의 typing을 끈다")
        void disconnectClearsTyping() {
            List<ReplayEvent> events = new ArrayList<>(conversation().subList(0, 6));
            events.add(event(7, EventType.DISCONNECT, null, b, Map.of(), null, t(4)));
            SessionReducer reducer = SessionReducer.empty();
            events.forEach(reducer::apply);
            assertThat(participant(reducer.state(), b).typingTs()).isNull();
        }
    }

    @Test
    @DisplayName("스냅샷에서 이어서 fold한 결과는 어느 지점에서 끊어도 처음부터 fold한 결과와 같다")
    void snapshotThenReplayEqualsFullReplay() {
        List<ReplayEvent> events = conversation();
        RestoredState full = foldUpTo(events.size());
        IntStream.rangeClosed(0, events.size()).forEach(cut -> {
            SessionReducer head = SessionReducer.empty();
            events.subList(0, cut).forEach(head::apply);
            SessionReducer resumed = cut == 0 ? SessionReducer.empty() : SessionReducer.from(head.state());
            events.subList(cut, events.size()).forEach(resumed::apply);
            assertThat(resumed.state()).as("끊은 지점 %d", cut).isEqualTo(full);
        });
    }

    @Nested
    @DisplayName("해석할 수 없는 이벤트는 건너뛰지 않고 실패한다")
    class Unsupported {

        @Test
        @DisplayName("seq에 빈틈이 있으면")
        void gap() {
            SessionReducer reducer = SessionReducer.empty();
            reducer.apply(conversation().get(0));
            assertThatThrownBy(() -> reducer.apply(conversation().get(2)))
                    .isInstanceOfSatisfying(ReplayUnsupportedException.class, e -> assertThat(e.seq()).isEqualTo(3));
        }

        @Test
        @DisplayName("모르는 스키마 버전이면")
        void schemaVersion() {
            ReplayEvent future = new ReplayEvent(1, EventType.SESSION_CREATED, a, null, Map.of(), null, t(0), (short) 2);
            assertThatThrownBy(() -> SessionReducer.empty().apply(future)).isInstanceOf(ReplayUnsupportedException.class);
        }

        @Test
        @DisplayName("참여하지 않은 사용자의 이벤트면")
        void unknownParticipant() {
            SessionReducer reducer = SessionReducer.empty();
            conversation().subList(0, 2).forEach(reducer::apply);
            ReplayEvent stranger = event(3, EventType.TYPING_STARTED, b, b, Map.of(), null, t(1));
            assertThatThrownBy(() -> reducer.apply(stranger)).isInstanceOf(ReplayUnsupportedException.class);
        }

        @Test
        @DisplayName("payload가 깨졌으면(필드 누락) 다른 예외가 새지 않고 같은 예외로 실패한다")
        void brokenPayload() {
            SessionReducer reducer = SessionReducer.empty();
            reducer.apply(conversation().get(0));
            ReplayEvent broken = event(2, EventType.JOIN, a, a, Map.of(), null, t(0));
            assertThatThrownBy(() -> reducer.apply(broken))
                    .isInstanceOfSatisfying(ReplayUnsupportedException.class, e -> assertThat(e.seq()).isEqualTo(2));
            assertThat(reducer.lastSeq()).isEqualTo(1);
        }
    }
}
