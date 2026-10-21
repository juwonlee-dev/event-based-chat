package com.eventchat.domain.session;

import com.eventchat.domain.session.SessionCommand.End;
import com.eventchat.domain.session.SessionCommand.Join;
import com.eventchat.domain.session.SessionCommand.Leave;
import com.eventchat.domain.session.SessionState.Participant;
import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
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

    public static Decision decide(SessionState state, SessionCommand command) {
        return switch (command) {
            case Join join -> join(state, join);
            case Leave leave -> leave(state, leave);
            case End end -> end(state, end);
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
                statusChanged(transition, null, false)), transition);
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
                statusChanged(transition, null, false)), transition);
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
        return new Decision.Accepted(List.of(statusChanged(transition, command.actorId(), true)), transition);
    }

    private static Optional<Rejection> checkParticipant(SessionState state, UUID actorId) {
        Optional<Participant> self = state.participant(actorId);
        if (self.isEmpty()) {
            return Optional.of(Rejection.NOT_PARTICIPANT);
        }
        return self.get().left() ? Optional.of(Rejection.PARTICIPANT_LEFT) : Optional.empty();
    }

    private static NewEvent statusChanged(Transition transition, UUID endedBy, boolean keyed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", transition.from().name());
        payload.put("to", transition.to().name());
        payload.put("reason", transition.reason().name());
        if (endedBy != null) {
            payload.put("endedBy", endedBy.toString());
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
