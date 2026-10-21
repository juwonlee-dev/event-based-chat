package com.eventchat.domain.session;

/**
 * 도메인 규칙으로 거절한 이유. 이름이 그대로 API 오류 코드가 된다.
 */
public enum Rejection {
    NOT_PARTICIPANT,
    PARTICIPANT_LEFT,
    ALREADY_JOINED,
    SESSION_NOT_JOINABLE,
    SESSION_NOT_STARTED,
    SESSION_NOT_ACTIVE
}
