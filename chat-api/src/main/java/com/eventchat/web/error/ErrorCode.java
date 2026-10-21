package com.eventchat.web.error;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트가 화면 문구를 매핑하는 오류 코드. HTTP 상태와 함께 problem+json의 {@code code}로 내려간다.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    CURSOR_INVALID(HttpStatus.BAD_REQUEST),
    MESSAGE_TOO_LONG(HttpStatus.BAD_REQUEST),
    EVENT_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST),
    INVALID_TIME_FORMAT(HttpStatus.BAD_REQUEST),
    AT_IN_FUTURE(HttpStatus.BAD_REQUEST),
    AUTH_INVALID(HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    NOT_PARTICIPANT(HttpStatus.FORBIDDEN),
    PARTICIPANT_LEFT(HttpStatus.FORBIDDEN),
    ADMIN_READ_ONLY(HttpStatus.FORBIDDEN),
    ADMIN_ONLY(HttpStatus.FORBIDDEN),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    SESSION_NOT_JOINABLE(HttpStatus.CONFLICT),
    ALREADY_JOINED(HttpStatus.CONFLICT),
    SESSION_NOT_STARTED(HttpStatus.CONFLICT),
    SESSION_NOT_ACTIVE(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
    BEFORE_SESSION_START(HttpStatus.UNPROCESSABLE_CONTENT),
    REPLAY_UNSUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    RESTORE_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
