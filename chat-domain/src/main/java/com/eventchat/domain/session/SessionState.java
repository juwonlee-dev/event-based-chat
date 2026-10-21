package com.eventchat.domain.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 판정에 필요한 만큼만 담은 세션의 현재 상태.
 */
public record SessionState(SessionStatus status, List<Participant> participants) {

    public Optional<Participant> participant(UUID userId) {
        return participants.stream().filter(p -> p.userId().equals(userId)).findFirst();
    }

    public record Participant(UUID userId, boolean left) {
    }
}
