package com.eventchat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RelayEnvelopeTest {

    private static final byte[] JSON = "{\"seq\":7,\"payload\":{\"content\":\"안녕하세요\"}}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("첫 줄의 발행 시각·traceparent와 본문 JSON이 그대로 돌아온다")
    void roundTrip() {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        RelayEnvelope decoded = RelayEnvelope.decode(new RelayEnvelope(1_761_100_000_123L, traceparent, JSON).encode());

        assertThat(decoded.publishedAtMillis()).isEqualTo(1_761_100_000_123L);
        assertThat(decoded.traceparent()).isEqualTo(traceparent);
        assertThat(decoded.json()).isEqualTo(JSON);
    }

    @Test
    @DisplayName("추적 중이 아니면 traceparent는 빈 문자열로 오간다")
    void withoutTrace() {
        RelayEnvelope decoded = RelayEnvelope.decode(new RelayEnvelope(1L, "", JSON).encode());
        assertThat(decoded.traceparent()).isEmpty();
        assertThat(decoded.json()).isEqualTo(JSON);
    }

    @Test
    @DisplayName("머리 줄이 없는 메시지는 거부한다")
    void noHeader() {
        assertThatThrownBy(() -> RelayEnvelope.decode(JSON)).isInstanceOf(IllegalArgumentException.class);
    }
}
