package com.eventchat.domain.session;

public record Transition(SessionStatus from, SessionStatus to, TransitionReason reason) {
}
