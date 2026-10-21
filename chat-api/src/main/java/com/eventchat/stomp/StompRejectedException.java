package com.eventchat.stomp;

/**
 * 연결 단위 오류. STOMP ERROR(message 헤더 = code)를 보내고 연결을 닫는다.
 * 구독·전송 거부처럼 연결을 유지해야 하는 경우에는 쓰지 않는다.
 */
public class StompRejectedException extends RuntimeException {

    private final String code;

    public StompRejectedException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
