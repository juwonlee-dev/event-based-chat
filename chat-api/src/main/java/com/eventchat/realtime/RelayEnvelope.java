package com.eventchat.realtime;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 노드 사이 Redis 메시지 형식. 첫 줄이 내부 메타데이터, 나머지가 클라이언트에 그대로 보낼 JSON이다.
 * <pre>
 * {발행 시각 epoch ms} {traceparent 또는 비어 있음}\n
 * {EventView JSON}
 * </pre>
 * 받는 노드가 JSON을 다시 파싱·직렬화하지 않고 첫 줄만 떼어 보낸다(압축 JSON에는 줄바꿈이 없다).
 *
 * @param traceparent W3C traceparent. 추적 중이 아니면 빈 문자열
 */
record RelayEnvelope(long publishedAtMillis, String traceparent, byte[] json) {

    byte[] encode() {
        byte[] head = (publishedAtMillis + " " + traceparent + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] out = Arrays.copyOf(head, head.length + json.length);
        System.arraycopy(json, 0, out, head.length, json.length);
        return out;
    }

    static RelayEnvelope decode(byte[] message) {
        for (int i = 0; i < message.length; i++) {
            if (message[i] == '\n') {
                String[] head = new String(message, 0, i, StandardCharsets.US_ASCII).split(" ", 2);
                return new RelayEnvelope(Long.parseLong(head[0]), head.length > 1 ? head[1] : "",
                        Arrays.copyOfRange(message, i + 1, message.length));
            }
        }
        throw new IllegalArgumentException("relay envelope has no header line");
    }
}
