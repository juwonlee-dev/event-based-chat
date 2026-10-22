package com.eventchat.domain.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventchat.domain.session.Decision.Accepted;
import com.eventchat.domain.session.Decision.Rejected;
import com.eventchat.domain.session.Decision.Suppressed;
import com.eventchat.domain.session.SessionCommand.End;
import com.eventchat.domain.session.SessionCommand.Join;
import com.eventchat.domain.session.SessionCommand.Leave;
import com.eventchat.domain.session.SessionCommand.Message;
import com.eventchat.domain.session.SessionCommand.Presence;
import com.eventchat.domain.session.SessionCommand.ReadCursor;
import com.eventchat.domain.session.SessionCommand.Typing;
import com.eventchat.domain.session.SessionState.Participant;
import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SessionDeciderTest {

    private static final IngestPolicy POLICY = new IngestPolicy(Duration.ofSeconds(10), Duration.ofSeconds(3),
            Duration.ofSeconds(1));
    private static final Instant T0 = Instant.parse("2026-10-22T00:00:00Z");

    private final UUID creator = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();

    private Participant joined(UUID userId) {
        return new Participant(userId, false, null, 0, null, PresenceStatus.OFFLINE, 0);
    }

    private Participant left(UUID userId) {
        return new Participant(userId, true, null, 0, null, PresenceStatus.OFFLINE, 0);
    }

    private SessionState waiting() {
        return new SessionState(SessionStatus.WAITING, 2, List.of(joined(creator)));
    }

    private SessionState inProgress(Participant... participants) {
        List<Participant> list = participants.length == 0 ? List.of(joined(creator), joined(other)) : List.of(participants);
        return new SessionState(SessionStatus.IN_PROGRESS, 5, list);
    }

    private Join join(UUID userId) {
        Map<UUID, PresenceSnapshot> baseline = new LinkedHashMap<>();
        baseline.put(creator, new PresenceSnapshot(PresenceStatus.ONLINE, 10));
        baseline.putIfAbsent(userId, PresenceSnapshot.UNKNOWN);
        return new Join(userId, Role.CUSTOMER, Category.BANK, "고객", baseline);
    }

    private static Decision decide(SessionState state, SessionCommand command) {
        return SessionDecider.decide(state, command, POLICY);
    }

    @Nested
    @DisplayName("상태 머신")
    class StateMachine {

        @Test
        @DisplayName("대기 중 세션에 참여하면 JOIN과 진행 중 전이가 함께 생기고, 전이에 두 사람의 presence 기준값이 실린다")
        void joinWaiting() {
            Decision decision = decide(waiting(), join(other));

            assertThat(decision).isInstanceOf(Accepted.class);
            Accepted accepted = (Accepted) decision;
            assertThat(accepted.events()).extracting(NewEvent::type)
                    .containsExactly(EventType.JOIN, EventType.SESSION_STATUS_CHANGED);
            assertThat(accepted.transition()).isEqualTo(new Transition(SessionStatus.WAITING, SessionStatus.IN_PROGRESS,
                    TransitionReason.JOINED));
            @SuppressWarnings("unchecked")
            Map<String, Object> baseline = (Map<String, Object>) accepted.events().get(1).payload().get("presenceBaseline");
            assertThat(baseline).containsOnlyKeys(creator.toString(), other.toString());
            assertThat(baseline.get(creator.toString())).isEqualTo(Map.of("state", "ONLINE", "ver", 10L));
        }

        @Test
        @DisplayName("멱등 키는 JOIN에만 붙고 뒤따르는 전이 이벤트에는 붙지 않는다")
        void joinKeysOnlyJoinEvent() {
            Accepted accepted = (Accepted) decide(waiting(), join(other));
            assertThat(accepted.events()).extracting(NewEvent::keyed).containsExactly(true, false);
        }

        @Test
        @DisplayName("진행 중이거나 끝난 세션에는 새로 참여할 수 없다")
        void joinNotWaiting() {
            assertThat(decide(inProgress(), join(outsider))).isEqualTo(new Rejected(Rejection.SESSION_NOT_JOINABLE));
            SessionState completed = new SessionState(SessionStatus.COMPLETED, 6, List.of(joined(creator), joined(other)));
            assertThat(decide(completed, join(outsider))).isEqualTo(new Rejected(Rejection.SESSION_NOT_JOINABLE));
        }

        @Test
        @DisplayName("이미 참여한 사람은 ALREADY_JOINED, 퇴장한 사람은 다시 참여할 수 없다")
        void rejoin() {
            assertThat(decide(waiting(), join(creator))).isEqualTo(new Rejected(Rejection.ALREADY_JOINED));
            SessionState suspended = new SessionState(SessionStatus.SUSPENDED, 3, List.of(left(creator)));
            assertThat(decide(suspended, join(creator))).isEqualTo(new Rejected(Rejection.PARTICIPANT_LEFT));
        }

        @Test
        @DisplayName("대기 중에 만든 사람이 나가면 바로 중단된다")
        void leaveWaiting() {
            Accepted accepted = (Accepted) decide(waiting(), new Leave(creator));
            assertThat(accepted.transition()).isEqualTo(new Transition(SessionStatus.WAITING, SessionStatus.SUSPENDED,
                    TransitionReason.LEFT));
            assertThat(accepted.events()).extracting(NewEvent::type)
                    .containsExactly(EventType.LEAVE, EventType.SESSION_STATUS_CHANGED);
        }

        @Test
        @DisplayName("진행 중에 누가 나가도 중단되고, 끝내면 완료된다")
        void leaveAndEndInProgress() {
            assertThat(((Accepted) decide(inProgress(), new Leave(other))).transition().to())
                    .isEqualTo(SessionStatus.SUSPENDED);
            Accepted ended = (Accepted) decide(inProgress(), new End(creator));
            assertThat(ended.transition().to()).isEqualTo(SessionStatus.COMPLETED);
            assertThat(ended.events()).singleElement().satisfies(e -> {
                assertThat(e.type()).isEqualTo(EventType.SESSION_STATUS_CHANGED);
                assertThat(e.keyed()).isTrue();
                assertThat(e.payload()).containsEntry("endedBy", creator.toString());
            });
        }

        @Test
        @DisplayName("대기 중에는 종료할 수 없다(퇴장만 할 수 있다)")
        void endWaiting() {
            assertThat(decide(waiting(), new End(creator))).isEqualTo(new Rejected(Rejection.SESSION_NOT_STARTED));
        }

        @ParameterizedTest
        @EnumSource(value = SessionStatus.class, names = {"SUSPENDED", "COMPLETED"})
        @DisplayName("끝난 세션에서는 퇴장·종료·메시지가 모두 SESSION_NOT_ACTIVE")
        void terminal(SessionStatus status) {
            SessionState state = new SessionState(status, 6, List.of(joined(creator), joined(other)));
            assertThat(decide(state, new Leave(creator))).isEqualTo(new Rejected(Rejection.SESSION_NOT_ACTIVE));
            assertThat(decide(state, new End(creator))).isEqualTo(new Rejected(Rejection.SESSION_NOT_ACTIVE));
            assertThat(decide(state, new Message(creator, "안녕하세요"))).isEqualTo(new Rejected(Rejection.SESSION_NOT_ACTIVE));
        }

        @Test
        @DisplayName("참여자 확인이 상태 확인보다 먼저다")
        void participantCheckComesFirst() {
            SessionState completed = new SessionState(SessionStatus.COMPLETED, 6, List.of(joined(creator), left(other)));
            assertThat(decide(completed, new Leave(outsider))).isEqualTo(new Rejected(Rejection.NOT_PARTICIPANT));
            assertThat(decide(completed, new Message(other, "x"))).isEqualTo(new Rejected(Rejection.PARTICIPANT_LEFT));
        }

        @Test
        @DisplayName("대기 중에는 메시지를 보낼 수 없다")
        void messageWaiting() {
            assertThat(decide(waiting(), new Message(creator, "x"))).isEqualTo(new Rejected(Rejection.SESSION_NOT_ACTIVE));
        }
    }

    @Nested
    @DisplayName("typing")
    class TypingRules {

        @Test
        @DisplayName("입력 중이 아니면 STARTED를 저장하고, 3초 안에 다시 온 STARTED는 저장하지 않는다")
        void startedThrottled() {
            assertThat(decide(inProgress(), new Typing(other, true, T0))).isInstanceOf(Accepted.class);
            SessionState typing = inProgress(joined(creator),
                    new Participant(other, false, T0, 0, null, PresenceStatus.ONLINE, 1));
            assertThat(decide(typing, new Typing(other, true, T0.plusSeconds(2)))).isInstanceOf(Suppressed.class);
            assertThat(decide(typing, new Typing(other, true, T0.plusSeconds(3)))).isInstanceOf(Accepted.class);
        }

        @Test
        @DisplayName("STOPPED는 입력 중일 때만 저장한다. 10초가 지난 STARTED는 이미 꺼진 것으로 본다")
        void stoppedOnlyWhenTyping() {
            assertThat(decide(inProgress(), new Typing(other, false, T0))).isInstanceOf(Suppressed.class);
            SessionState typing = inProgress(joined(creator),
                    new Participant(other, false, T0, 0, null, PresenceStatus.ONLINE, 1));
            assertThat(decide(typing, new Typing(other, false, T0.plusSeconds(9)))).isInstanceOf(Accepted.class);
            assertThat(decide(typing, new Typing(other, false, T0.plusSeconds(10)))).isInstanceOf(Suppressed.class);
        }
    }

    @Nested
    @DisplayName("읽음 커서")
    class ReadCursorRules {

        @Test
        @DisplayName("앞으로 간 커서만 저장하고, 마지막 seq보다 큰 값은 마지막 seq로 자른다")
        void advancesAndClamps() {
            Accepted accepted = (Accepted) decide(inProgress(), new ReadCursor(other, 99, 3, T0));
            assertThat(accepted.events()).singleElement().satisfies(e -> {
                assertThat(e.type()).isEqualTo(EventType.READ_CURSOR);
                assertThat(e.payload()).containsEntry("upToSeq", 5L).containsEntry("readMsgNo", 3);
            });
        }

        @Test
        @DisplayName("같은 위치나 뒤로 간 커서, 1초 안의 반복은 저장하지 않는다")
        void suppressed() {
            SessionState read = inProgress(joined(creator), new Participant(other, false, null, 4, T0,
                    PresenceStatus.ONLINE, 1));
            assertThat(decide(read, new ReadCursor(other, 4, 2, T0.plusSeconds(5)))).isInstanceOf(Suppressed.class);
            assertThat(decide(read, new ReadCursor(other, 3, 1, T0.plusSeconds(5)))).isInstanceOf(Suppressed.class);
            assertThat(decide(read, new ReadCursor(other, 5, 3, T0.plusMillis(900)))).isInstanceOf(Suppressed.class);
            assertThat(decide(read, new ReadCursor(other, 5, 3, T0.plusSeconds(1)))).isInstanceOf(Accepted.class);
        }
    }

    @Nested
    @DisplayName("presence")
    class PresenceRules {

        private final Map<String, Object> detail = Map.of("reason", "SOCKET_CLOSED");

        @Test
        @DisplayName("상태가 바뀌고 버전이 더 크면 DISCONNECT·RECONNECT를 남긴다(서버 이벤트라 멱등 키 없음)")
        void transition() {
            SessionState online = inProgress(joined(creator), new Participant(other, false, null, 0, null,
                    PresenceStatus.ONLINE, 100));
            Accepted accepted = (Accepted) decide(online, new Presence(other, PresenceStatus.OFFLINE, 101, detail));
            assertThat(accepted.events()).singleElement().satisfies(e -> {
                assertThat(e.type()).isEqualTo(EventType.DISCONNECT);
                assertThat(e.subjectId()).isEqualTo(other);
                assertThat(e.keyed()).isFalse();
            });
            assertThat(((Accepted) decide(inProgress(), new Presence(other, PresenceStatus.ONLINE, 1, Map.of())))
                    .events().getFirst().type()).isEqualTo(EventType.RECONNECT);
        }

        @Test
        @DisplayName("늦게 온 신호(버전이 같거나 작음)와 같은 상태의 반복은 저장하지 않는다")
        void staleOrSame() {
            SessionState online = inProgress(joined(creator), new Participant(other, false, null, 0, null,
                    PresenceStatus.ONLINE, 100));
            assertThat(decide(online, new Presence(other, PresenceStatus.OFFLINE, 100, detail))).isInstanceOf(Suppressed.class);
            assertThat(decide(online, new Presence(other, PresenceStatus.OFFLINE, 50, detail))).isInstanceOf(Suppressed.class);
            assertThat(decide(online, new Presence(other, PresenceStatus.ONLINE, 200, detail))).isInstanceOf(Suppressed.class);
        }

        @Test
        @DisplayName("진행 중이 아닌 세션과 퇴장한 참여자에게는 기록하지 않는다")
        void outOfScope() {
            assertThat(decide(waiting(), new Presence(creator, PresenceStatus.ONLINE, 1, Map.of())))
                    .isInstanceOf(Suppressed.class);
            SessionState leftOther = inProgress(joined(creator), left(other));
            assertThat(decide(leftOther, new Presence(other, PresenceStatus.ONLINE, 1, Map.of())))
                    .isInstanceOf(Suppressed.class);
        }
    }
}
