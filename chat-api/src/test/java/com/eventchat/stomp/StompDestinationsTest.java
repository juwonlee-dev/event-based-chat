package com.eventchat.stomp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StompDestinationsTest {

    private final UUID sessionId = UUID.randomUUID();

    @Test
    @DisplayName("세션 토픽 구독은 서버가 발행하는 목적지(소문자 UUID)와 똑같을 때만 허용한다")
    void topicIsLowercaseOnly() {
        String topic = StompDestinations.sessionTopic(sessionId);
        assertThat(StompDestinations.SESSION_TOPIC.matcher(topic).matches()).isTrue();
        assertThat(StompDestinations.SESSION_TOPIC.matcher("/topic/sessions/" + sessionId.toString().toUpperCase())
                .matches()).isFalse();
    }

    @Test
    @DisplayName("허용 목록 밖의 목적지는 맞지 않는다")
    void outsideAllowList() {
        assertThat(StompDestinations.SESSION_TOPIC.matcher("/topic/sessions/" + sessionId + "/x").matches()).isFalse();
        assertThat(StompDestinations.SESSION_TOPIC.matcher("/topic/sessions/not-a-uuid").matches()).isFalse();
        assertThat(StompDestinations.SESSION_EVENTS.matcher("/app/sessions/" + sessionId + "/events").matches())
                .isTrue();
        assertThat(StompDestinations.SESSION_EVENTS.matcher("/topic/sessions/" + sessionId).matches()).isFalse();
    }
}
