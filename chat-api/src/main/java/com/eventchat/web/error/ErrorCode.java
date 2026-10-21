package com.eventchat.web.error;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트가 화면 문구를 매핑하는 오류 코드. HTTP 상태와 함께 problem+json의 {@code code}로 내려간다.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    AUTH_INVALID(HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
