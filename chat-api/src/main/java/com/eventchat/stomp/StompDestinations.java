package com.eventchat.stomp;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 허용하는 목적지. 이 밖의 SUBSCRIBE·SEND는 연결을 닫는다.
 * 내장 브로커는 /topic으로 들어온 SEND를 그대로 뿌리므로, 막지 않으면 이벤트를 위조할 수 있다.
 */
final class StompDestinations {

    private static final String UUID_PATTERN = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";

    static final Pattern SESSION_TOPIC = Pattern.compile("/topic/sessions/" + UUID_PATTERN);
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
