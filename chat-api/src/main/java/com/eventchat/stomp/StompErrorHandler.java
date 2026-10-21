package com.eventchat.stomp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * 인바운드 처리 중 연결 단위 오류가 나면 ERROR 프레임의 message 헤더에 코드를 싣는다.
 * 클라이언트는 이 코드로 재연결 여부를 정한다(AUTH_TOKEN_EXPIRED면 갱신 후 재연결, AUTH_REVOKED면 중단 등).
 */
@Slf4j
@Component
public class StompErrorHandler extends StompSubProtocolErrorHandler {

    private final StompDelivery delivery;

    public StompErrorHandler(StompDelivery delivery) {
        this.delivery = delivery;
    }

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        StompRejectedException rejected = findRejection(ex);
        String code;
        String detail;
        if (rejected != null) {
            code = rejected.code();
            detail = rejected.getMessage();
        } else {
            log.warn("STOMP inbound failed", ex);
            code = "INTERNAL_ERROR";
            detail = "요청을 처리하지 못했습니다.";
        }
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(code);
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(delivery.errorBody(code, detail), accessor.getMessageHeaders());
    }

    private static StompRejectedException findRejection(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof StompRejectedException rejected) {
                return rejected;
            }
        }
        return null;
    }
}
