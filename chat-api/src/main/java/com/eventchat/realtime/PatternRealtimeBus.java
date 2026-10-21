package com.eventchat.realtime;

import com.eventchat.presence.PresenceTracker;
import com.eventchat.session.EventView;
import com.eventchat.stomp.StompDelivery;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 패턴 구독 구현. 모든 노드가 기동할 때 {@code PSUBSCRIBE chat:s:*}를 한 번 하고,
 * 쓰기 노드는 커밋 직후 {@code PUBLISH chat:s:{sessionId}}를 보낸다.
 * 발행한 노드도 자기 구독자에게는 Redis를 거쳐 받은 것을 보낸다(전달 경로를 하나로 둔다).
 * <ul>
 *   <li>발행이 실패하면 저하 상태로 바꾸고 자기 노드의 구독자에게만 직접 전달한다.</li>
 *   <li>구독 연결이 다시 붙으면(출력 버퍼 초과, Redis 재시작 등) 그 사이 메시지를 잃었을 수 있으므로
 *       로컬 세션 구독 전체에 SYSTEM_RESYNC를 보내고, 이 노드의 연결을 presence에 다시 등록한다.</li>
 *   <li>메시지 첫 줄에 발행 시각과 traceparent를 실어({@link RelayEnvelope}) 받는 노드에서 추적을 잇고
 *       커밋 → 로컬 전송 지연을 잰다.</li>
 * </ul>
 */
@Slf4j
@Component
public class PatternRealtimeBus implements RealtimeBus, MessageListener, SubscriptionListener {

    static final String CHANNEL_PREFIX = "chat:s:";
    static final String PATTERN = CHANNEL_PREFIX + "*";

    private final StringRedisTemplate redis;
    private final StompDelivery delivery;
    private final RealtimeHealth health;
    private final PresenceTracker presence;
    private final Tracer tracer;
    private final Propagator propagator;
    private final MeterRegistry meters;
    private final Timer deliveryLatency;
    private final AtomicBoolean subscribedOnce = new AtomicBoolean();

    public PatternRealtimeBus(StringRedisTemplate redis, StompDelivery delivery, RealtimeHealth health,
            PresenceTracker presence, Tracer tracer, Propagator propagator, MeterRegistry meters) {
        this.redis = redis;
        this.delivery = delivery;
        this.health = health;
        this.presence = presence;
        this.tracer = tracer;
        this.propagator = propagator;
        this.meters = meters;
        this.deliveryLatency = Timer.builder("chat.realtime.delivery.latency")
                .description("커밋 직후 발행부터 이 노드의 로컬 구독자에게 보내기까지")
                .publishPercentileHistogram()
                .register(meters);
    }

    @Override
    public void publish(UUID sessionId, EventView event) {
        byte[] json = delivery.json(event);
        byte[] channel = (CHANNEL_PREFIX + sessionId).getBytes(StandardCharsets.UTF_8);
        byte[] envelope = new RelayEnvelope(System.currentTimeMillis(), traceparent(), json).encode();
        try {
            redis.execute((RedisCallback<Long>) connection -> connection.publish(channel, envelope));
            meters.counter("chat.realtime.publish", "result", "ok").increment();
        } catch (RuntimeException e) {
            log.warn("realtime publish failed, delivering locally: session={} seq={}", sessionId, event.seq(), e);
            meters.counter("chat.realtime.publish", "result", "local_fallback").increment();
            health.markDegraded();
            delivery.toTopic(sessionId, json);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        UUID sessionId = UUID.fromString(channel.substring(CHANNEL_PREFIX.length()));
        RelayEnvelope envelope = RelayEnvelope.decode(message.getBody());
        if (envelope.traceparent().isEmpty()) {
            deliver(sessionId, envelope);
            return;
        }
        // 발행한 노드의 요청 추적을 이어서, REST → DB → Redis → 받는 노드가 한 트레이스로 보이게 한다.
        Span span = propagator.extract(Map.of("traceparent", envelope.traceparent()), Map::get)
                .name("realtime deliver")
                .kind(Span.Kind.CONSUMER)
                .tag("messaging.destination", channel)
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            deliver(sessionId, envelope);
        } finally {
            span.end();
        }
    }

    private void deliver(UUID sessionId, RelayEnvelope envelope) {
        delivery.toTopic(sessionId, envelope.json());
        deliveryLatency.record(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - envelope.publishedAtMillis())));
    }

    private String traceparent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return "";
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.getOrDefault("traceparent", "");
    }

    @Override
    public void onPatternSubscribed(byte[] pattern, long count) {
        if (subscribedOnce.compareAndSet(false, true)) {
            log.info("realtime pattern subscribed: {}", new String(pattern, StandardCharsets.UTF_8));
            return;
        }
        var sessions = delivery.locallySubscribedSessions();
        log.warn("realtime pattern re-subscribed, sending SYSTEM_RESYNC to {} local sessions", sessions.size());
        meters.counter("chat.realtime.resubscribed").increment();
        sessions.forEach(sessionId ->
                delivery.toTopic(sessionId, Map.of("kind", "SYSTEM_RESYNC", "sessionId", sessionId)));
        // Lettuce 이벤트 루프 스레드다. Redis 명령을 기다리는 일은 다른 스레드로 넘긴다.
        Thread.ofVirtual().start(() -> presence.reregister("RESUBSCRIBED"));
    }

    @Override
    public void onLocalSubscribe(UUID sessionId) {
        // 패턴 구독이라 세션별 구독 관리가 필요 없다.
    }

    @Override
    public void onLocalUnsubscribe(UUID sessionId) {
        // 패턴 구독이라 세션별 구독 관리가 필요 없다.
    }

    @Override
    public void awaitReady(UUID sessionId) {
        // 패턴 구독은 기동할 때 이미 확정되어 있다.
    }
}
