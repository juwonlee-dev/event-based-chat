package com.eventchat.session;

import com.eventchat.domain.session.Rejection;
import com.eventchat.domain.session.SessionStatus;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import java.util.Map;
import org.springframework.http.HttpStatus;

final class SessionErrors {

    private SessionErrors() {
    }

    static ApiException notFound() {
        return new ApiException(ErrorCode.SESSION_NOT_FOUND, "세션을 찾을 수 없습니다.");
    }

    static ApiException adminReadOnly() {
        return new ApiException(ErrorCode.ADMIN_READ_ONLY, "관리자는 세션에 참여하거나 상태를 바꿀 수 없습니다.");
    }

    static ApiException idempotencyKeyReused() {
        return new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "같은 멱등 키로 다른 요청을 보냈습니다.");
    }

    /** 409에는 화면에서 대기/종료를 구분할 수 있도록 현재 상태를 함께 싣는다. */
    static ApiException rejected(Rejection reason, SessionStatus status) {
        ErrorCode code = ErrorCode.valueOf(reason.name());
        Map<String, Object> properties = code.status() == HttpStatus.CONFLICT
                ? Map.of("sessionStatus", status.name())
                : Map.of();
        return new ApiException(code, detail(reason), properties, null);
    }

    private static String detail(Rejection reason) {
        return switch (reason) {
            case NOT_PARTICIPANT -> "세션 참여자가 아닙니다.";
            case PARTICIPANT_LEFT -> "이미 퇴장한 세션입니다.";
            case ALREADY_JOINED -> "이미 참여한 세션입니다.";
            case SESSION_NOT_JOINABLE -> "대기 중인 세션에만 참여할 수 있습니다.";
            case SESSION_NOT_STARTED -> "상대가 참여하기 전에는 종료할 수 없습니다. 퇴장만 할 수 있습니다.";
            case SESSION_NOT_ACTIVE -> "이미 중단되었거나 완료된 세션입니다.";
        };
    }
}
