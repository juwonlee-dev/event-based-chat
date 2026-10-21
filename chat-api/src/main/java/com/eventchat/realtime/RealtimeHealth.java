package com.eventchat.realtime;

import com.eventchat.stomp.StompDelivery;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis 중계 상태. 끊기면 이 노드의 연결 전원에게 SYSTEM_DEGRADED를 보내 보충 API를 주기적으로 부르게 하고,
 * 다시 붙으면 SYSTEM_RECOVERED를 보낸다. 쓰기는 Redis와 관계없이 계속된다(원천은 DB).
 */
@Slf4j
@Component
public class RealtimeHealth {

    private static final String SYSTEM_QUEUE = "/queue/system";

    private final StringRedisTemplate redis;
    private final StompDelivery delivery;
    private final RealtimeProperties properties;
    private final AtomicBoolean degraded = new AtomicBoolean();

    public RealtimeHealth(StringRedisTemplate redis, @Lazy StompDelivery delivery, RealtimeProperties properties) {
        this.redis = redis;
        this.delivery = delivery;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${chat.realtime.health-check-interval}",
            initialDelayString = "${chat.realtime.health-check-interval}")
    void check() {
        try {
            redis.execute((RedisCallback<String>) RedisConnection::ping);
            if (degraded.compareAndSet(true, false)) {
                log.info("realtime relay recovered");
                delivery.toAllUsers(SYSTEM_QUEUE, Map.of("kind", "SYSTEM_RECOVERED"));
            }
        } catch (RuntimeException e) {
            markDegraded();
        }
    }

    void markDegraded() {
        if (degraded.compareAndSet(false, true)) {
            log.warn("realtime relay degraded, clients fall back to polling");
            delivery.toAllUsers(SYSTEM_QUEUE, Map.of("kind", "SYSTEM_DEGRADED",
                    "pollIntervalMs", properties.degradedPollInterval().toMillis()));
        }
    }
}
