package com.eventchat.persistence.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Kafka로 내보낼 메시지. 이벤트와 같은 트랜잭션에 쌓고, relay가 발행한 뒤 삭제한다.
 */
@Entity
@Table(name = "event_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    private String traceparent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static EventOutbox of(String topic, String key, Map<String, Object> payload) {
        EventOutbox outbox = new EventOutbox();
        outbox.topic = topic;
        outbox.key = key;
        outbox.payload = payload;
        outbox.createdAt = Instant.now();
        return outbox;
    }
}
