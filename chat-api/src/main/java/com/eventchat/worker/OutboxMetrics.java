package com.eventchat.worker;

import com.eventchat.persistence.session.EventOutboxRepository;
import com.eventchat.persistence.session.OutboxBacklog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * outbox 적체. 가장 오래된 행의 나이가 relay·Kafka 장애를 가장 먼저 드러낸다(알림 기준 60초).
 * 지표를 읽을 때마다 DB를 조회하지 않도록 15초마다 값을 갱신해 둔다.
 */
@Slf4j
@Component
class OutboxMetrics {

    private final EventOutboxRepository outbox;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeMillis = new AtomicLong();

    OutboxMetrics(EventOutboxRepository outbox, MeterRegistry meters) {
        this.outbox = outbox;
        Gauge.builder("chat.outbox.pending", pending, AtomicLong::get).register(meters);
        Gauge.builder("chat.outbox.oldest.age", oldestAgeMillis, v -> v.get() / 1000.0).baseUnit("seconds")
                .register(meters);
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    void refresh() {
        try {
            OutboxBacklog backlog = outbox.backlog();
            pending.set(backlog.pending());
            oldestAgeMillis.set(backlog.oldestCreatedAt() == null ? 0
                    : Duration.between(backlog.oldestCreatedAt(), Instant.now()).toMillis());
        } catch (RuntimeException e) {
            log.debug("outbox backlog query failed", e);
        }
    }
}
