package com.eventchat.realtime;

import com.eventchat.stomp.StompDelivery;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    private final AtomicLong usedMemory = new AtomicLong();
    private final AtomicLong pubsubPatterns = new AtomicLong();

    public RealtimeHealth(StringRedisTemplate redis, @Lazy StompDelivery delivery, RealtimeProperties properties,
            MeterRegistry meters) {
        this.redis = redis;
        this.delivery = delivery;
        this.properties = properties;
        Gauge.builder("chat.realtime.degraded", degraded, d -> d.get() ? 1 : 0).register(meters);
        Gauge.builder("chat.redis.used.memory", usedMemory, AtomicLong::get).baseUnit("bytes").register(meters);
        Gauge.builder("chat.redis.pubsub.patterns", pubsubPatterns, AtomicLong::get).register(meters);
    }

    /** 구독 출력 버퍼도 Redis 메모리(maxmemory, noeviction)에 잡히므로 함께 본다. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    void collectRedisInfo() {
        try {
            Properties memory = redis.execute((RedisCallback<Properties>) c -> c.serverCommands().info("memory"));
            Properties stats = redis.execute((RedisCallback<Properties>) c -> c.serverCommands().info("stats"));
            usedMemory.set(Long.parseLong(memory.getProperty("used_memory", "0")));
            pubsubPatterns.set(Long.parseLong(stats.getProperty("pubsub_patterns", "0")));
        } catch (RuntimeException e) {
            log.debug("redis info failed", e);
        }
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
