package com.eventchat.domain.session;

/**
 * 상태가 바뀐 이유. 종단 전이(LEFT, ENDED)는 세션의 end_reason으로도 남는다.
 */
public enum TransitionReason {
    JOINED,
    LEFT,
    ENDED
}
