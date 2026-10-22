package com.eventchat.stomp;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 허용하는 목적지. 이 밖의 SUBSCRIBE·SEND는 연결을 닫는다.
 * 내장 브로커는 /topic으로 들어온 SEND를 그대로 뿌리므로, 막지 않으면 이벤트를 위조할 수 있다.
 */
final class StompDestinations {

    private static final String UUID_PATTERN = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";
    /**
     * 구독은 소문자 UUID만 받는다. 내장 브로커는 목적지를 문자열 그대로 비교하고 서버는 UUID.toString()(소문자)으로
     * 발행하므로, 대문자로 구독하면 인가와 resume는 통과해도 라이브 이벤트를 하나도 받지 못한다.
     */
    private static final String LOWERCASE_UUID_PATTERN = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";

    static final Pattern SESSION_TOPIC = Pattern.compile("/topic/sessions/" + LOWERCASE_UUID_PATTERN);
    static final Pattern SESSION_EVENTS = Pattern.compile("/app/sessions/" + UUID_PATTERN + "/events");
    static final String RECEIPTS = "/queue/receipts";
    static final String SYSTEM = "/queue/system";
    static final String USER_RECEIPTS = "/user" + RECEIPTS;
    static final String USER_SYSTEM = "/user" + SYSTEM;

    private StompDestinations() {
    }

    static String sessionTopic(UUID sessionId) {
        return "/topic/sessions/" + sessionId;
    }
}
