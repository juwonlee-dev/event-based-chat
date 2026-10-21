package com.eventchat.domain.session;

public enum SessionStatus {
    WAITING,
    IN_PROGRESS,
    SUSPENDED,
    COMPLETED;

    /** 중단·완료된 세션에서는 어떤 이벤트도 주고받을 수 없다. */
    public boolean isTerminal() {
        return this == SUSPENDED || this == COMPLETED;
    }
}
