package com.eventchat.realtime;

import com.eventchat.session.EventView;
import com.eventchat.stomp.StompDelivery;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 패턴 구독 구현. 모든 노드가 기동할 때 {@code PSUBSCRIBE chat:s:*}를 한 번 하고,
 * 쓰기 노드는 커밋 직후 {@code PUBLISH chat:s:{sessionId}}를 보낸다.
 * 발행한 노드도 자기 구독자에게는 Redis를 거쳐 받은 것을 보낸다(전달 경로를 하나로 둔다).
 * 발행이 실패하면 자기 노드의 구독자에게만 직접 전달한다.
 */
@Slf4j
@Component
public class PatternRealtimeBus implements RealtimeBus, MessageListener {

    static final String CHANNEL_PREFIX = "chat:s:";
    static final String PATTERN = CHANNEL_PREFIX + "*";

    private final StringRedisTemplate redis;
    private final StompDelivery delivery;

    public PatternRealtimeBus(StringRedisTemplate redis, StompDelivery delivery) {
        this.redis = redis;
        this.delivery = delivery;
    }

    @Override
    public void publish(UUID sessionId, EventView event) {
        byte[] json = delivery.json(event);
        byte[] channel = (CHANNEL_PREFIX + sessionId).getBytes(StandardCharsets.UTF_8);
        try {
            redis.execute((RedisCallback<Long>) connection -> connection.publish(channel, json));
        } catch (RuntimeException e) {
            log.warn("realtime publish failed, delivering locally: session={} seq={}", sessionId, event.seq(), e);
            delivery.toTopic(sessionId, json);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        delivery.toTopic(UUID.fromString(channel.substring(CHANNEL_PREFIX.length())), message.getBody());
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
