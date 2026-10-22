package com.eventchat.domain.session;

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
import java.util.Optional;
import java.util.UUID;

/**
 * 세션 상태 머신. 상태와 명령만 보고 판정하는 순수 함수라서 트랜잭션·저장은 호출하는 쪽이 맡는다.
 *
 * <pre>
 * 현재 \ 명령    join(미참여자)        leave              end
 * WAITING        → IN_PROGRESS         생성자 → SUSPENDED  SESSION_NOT_STARTED
 * IN_PROGRESS    SESSION_NOT_JOINABLE  → SUSPENDED         → COMPLETED
 * 종단 상태      SESSION_NOT_JOINABLE  SESSION_NOT_ACTIVE  SESSION_NOT_ACTIVE
 * </pre>
 * 참여자 확인(NOT_PARTICIPANT, PARTICIPANT_LEFT)이 상태 확인보다 먼저다.
 * 메시지·typing·읽음 커서는 IN_PROGRESS에서만 받고, typing·읽음은 같은 상태의 반복이면 저장하지 않는다.
 * 연결 상태(presence)도 IN_PROGRESS에서만, 상태가 실제로 바뀔 때만 기록한다.
 */
public final class SessionDecider {

    private SessionDecider() {
    }

    /** 세션 생성: 생성 이벤트와 생성자의 참여 이벤트. 멱등 키는 생성 이벤트에 남긴다. */
    public static List<NewEvent> create(UUID creatorId, Role role, Category category, String displayName,
            String title) {
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("createdBy", creatorId.toString());
        created.put("title", title);
        created.put("category", category.name());
        return List.of(
                new NewEvent(EventType.SESSION_CREATED, null, created, true),
                new NewEvent(EventType.JOIN, creatorId, joinPayload(role, category, displayName), false));
    }

    public static Decision decide(SessionState state, SessionCommand command, IngestPolicy policy) {
        return switch (command) {
            case Join join -> join(state, join);
            case Leave leave -> leave(state, leave);
            case End end -> end(state, end);
            case Message message -> message(state, message);
            case Typing typing -> typing(state, typing, policy);
            case ReadCursor read -> read(state, read, policy);
            case Presence presence -> presence(state, presence);
        };
    }

    private static Decision join(SessionState state, Join command) {
        Optional<Participant> self = state.participant(command.actorId());
        if (self.isPresent()) {
            return new Decision.Rejected(self.get().left() ? Rejection.PARTICIPANT_LEFT : Rejection.ALREADY_JOINED);
        }
        if (state.status() != SessionStatus.WAITING) {
            return new Decision.Rejected(Rejection.SESSION_NOT_JOINABLE);
        }
        Transition transition = new Transition(SessionStatus.WAITING, SessionStatus.IN_PROGRESS,
                TransitionReason.JOINED);
        return new Decision.Accepted(List.of(
                new NewEvent(EventType.JOIN, command.actorId(),
                        joinPayload(command.role(), command.category(), command.displayName()), true),
                statusChanged(transition, null, command.presenceBaseline(), false)), transition);
    }

    private static Decision leave(SessionState state, Leave command) {
        Optional<Rejection> notAllowed = checkParticipant(state, command.actorId());
        if (notAllowed.isPresent()) {
            return new Decision.Rejected(notAllowed.get());
        }
        if (state.status().isTerminal()) {
            return new Decision.Rejected(Rejection.SESSION_NOT_ACTIVE);
        }
        Transition transition = new Transition(state.status(), SessionStatus.SUSPENDED, TransitionReason.LEFT);
        return new Decision.Accepted(List.of(
                new NewEvent(EventType.LEAVE, command.actorId(), Map.of(), true),
                statusChanged(transition, null, null, false)), transition);
    }

    private static Decision end(SessionState state, End command) {
        Optional<Rejection> notAllowed = checkParticipant(state, command.actorId());
        if (notAllowed.isPresent()) {
            return new Decision.Rejected(notAllowed.get());
        }
        if (state.status() == SessionStatus.WAITING) {
            return new Decision.Rejected(Rejection.SESSION_NOT_STARTED);
        }
        if (state.status().isTerminal()) {
            return new Decision.Rejected(Rejection.SESSION_NOT_ACTIVE);
        }
        Transition transition = new Transition(SessionStatus.IN_PROGRESS, SessionStatus.COMPLETED,
                TransitionReason.ENDED);
        return new Decision.Accepted(List.of(statusChanged(transition, command.actorId(), null, true)), transition);
    }

    /** 본문은 payload.content로 넘기고, 저장할 때 별도 컬럼으로 옮긴다. 멱등 키(clientEventId)는 필수다. */
    private static Decision message(SessionState state, Message command) {
        Optional<Rejection> notAllowed = checkActive(state, command.actorId());
        if (notAllowed.isPresent()) {
            return new Decision.Rejected(notAllowed.get());
        }
        return new Decision.Accepted(List.of(new NewEvent(EventType.MESSAGE, null,
                Map.of("content", command.content()), true)), null);
    }

    /**
     * STARTED는 입력 중이 아니거나 마지막 STARTED로부터 최소 간격이 지났을 때만,
     * STOPPED는 입력 중일 때만 저장한다.
     */
    private static Decision typing(SessionState state, Typing command, IngestPolicy policy) {
        Optional<Rejection> notAllowed = checkActive(state, command.actorId());
        if (notAllowed.isPresent()) {
            return new Decision.Rejected(notAllowed.get());
        }
        Instant typingAt = state.participant(command.actorId()).orElseThrow().typingAt();
        boolean typing = typingAt != null && elapsed(typingAt, command.at()).compareTo(policy.typingTtl()) < 0;
        boolean store = command.started()
                ? !typing || elapsed(typingAt, command.at()).compareTo(policy.typingMinStoreInterval()) >= 0
                : typing;
        if (!store) {
            return new Decision.Suppressed();
        }
        EventType type = command.started() ? EventType.TYPING_STARTED : EventType.TYPING_STOPPED;
        return new Decision.Accepted(List.of(new NewEvent(type, command.actorId(), Map.of(), false)), null);
    }

    /**
     * 커서는 메시지 기준이다(호출하는 쪽이 요청 seq 이하의 마지막 MESSAGE로 맞춰 넘긴다). 그래서 READ_CURSOR·typing·presence
     * 같은 비메시지 이벤트만 늘어난 경우는 앞으로 간 것이 아니다. 앞으로 갔으면서 최소 간격이 지났을 때만 저장한다.
     */
    private static Decision read(SessionState state, ReadCursor command, IngestPolicy policy) {
        Optional<Rejection> notAllowed = checkActive(state, command.actorId());
        if (notAllowed.isPresent()) {
            return new Decision.Rejected(notAllowed.get());
        }
        Participant self = state.participant(command.actorId()).orElseThrow();
        long upToSeq = Math.min(command.upToSeq(), state.lastSeq());
        boolean advanced = upToSeq > self.readSeq();
        boolean tooSoon = self.lastReadStoreAt() != null
                && elapsed(self.lastReadStoreAt(), command.at()).compareTo(policy.readMinStoreInterval()) < 0;
        if (!advanced || tooSoon) {
            return new Decision.Suppressed();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("upToSeq", upToSeq);
        payload.put("readMsgNo", command.readMsgNo());
        return new Decision.Accepted(List.of(new NewEvent(EventType.READ_CURSOR, command.actorId(), payload, false)),
                null);
    }

    /**
     * 진행 중인 세션의 참여자에게만, 이미 반영한 것보다 새 버전이면서 상태가 실제로 바뀌었을 때만 기록한다.
     * 늦게 온 신호가 이후 상태를 덮지 못하고, 같은 상태의 반복(Redis 재시작 뒤 재등록 등)도 걸러진다.
     * 서버가 만든 명령이라 거절하지 않고 저장만 하지 않는다.
     */
    private static Decision presence(SessionState state, Presence command) {
        Optional<Participant> self = state.participant(command.userId());
        if (state.status() != SessionStatus.IN_PROGRESS || self.isEmpty() || self.get().left()
                || command.ver() <= self.get().presenceVer() || command.status() == self.get().presence()) {
            return new Decision.Suppressed();
        }
        EventType type = command.status() == PresenceStatus.ONLINE ? EventType.RECONNECT : EventType.DISCONNECT;
        return new Decision.Accepted(List.of(new NewEvent(type, command.userId(), command.detail(), false)), null);
    }

    private static Optional<Rejection> checkActive(SessionState state, UUID actorId) {
        Optional<Rejection> notAllowed = checkParticipant(state, actorId);
        if (notAllowed.isPresent()) {
            return notAllowed;
        }
        return state.status() == SessionStatus.IN_PROGRESS ? Optional.empty() : Optional.of(Rejection.SESSION_NOT_ACTIVE);
    }

    private static Duration elapsed(Instant from, Instant to) {
        return Duration.between(from, to);
    }

    private static Optional<Rejection> checkParticipant(SessionState state, UUID actorId) {
        Optional<Participant> self = state.participant(actorId);
        if (self.isEmpty()) {
            return Optional.of(Rejection.NOT_PARTICIPANT);
        }
        return self.get().left() ? Optional.of(Rejection.PARTICIPANT_LEFT) : Optional.empty();
    }

    private static NewEvent statusChanged(Transition transition, UUID endedBy,
            Map<UUID, PresenceSnapshot> presenceBaseline, boolean keyed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", transition.from().name());
        payload.put("to", transition.to().name());
        payload.put("reason", transition.reason().name());
        if (endedBy != null) {
            payload.put("endedBy", endedBy.toString());
        }
        if (presenceBaseline != null) {
            Map<String, Object> baseline = new LinkedHashMap<>();
            presenceBaseline.forEach((userId, snapshot) -> baseline.put(userId.toString(),
                    Map.of("state", snapshot.status().name(), "ver", snapshot.ver())));
            payload.put("presenceBaseline", baseline);
        }
        return new NewEvent(EventType.SESSION_STATUS_CHANGED, null, payload, keyed);
    }

    private static Map<String, Object> joinPayload(Role role, Category category, String displayName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", role.name());
        payload.put("category", category.name());
        payload.put("displayName", displayName);
        return payload;
    }
}
