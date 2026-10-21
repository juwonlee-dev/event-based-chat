package com.eventchat.presence;

import com.eventchat.domain.session.PresenceSnapshot;
import com.eventchat.domain.session.PresenceStatus;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis에 있는 사용자별 연결 상태({@code pr:{userId}})를 읽는다.
 * 기록이 없거나 Redis를 읽지 못하면 OFFLINE, 버전 0으로 본다(이후 대조 작업이 맞춘다).
 */
@Slf4j
@Component
public class PresenceSnapshots {

    private final StringRedisTemplate redis;

    public PresenceSnapshots(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Map<UUID, PresenceSnapshot> read(Collection<UUID> userIds) {
        Map<UUID, PresenceSnapshot> snapshots = new LinkedHashMap<>();
        for (UUID userId : userIds) {
            snapshots.put(userId, read(userId));
        }
        return snapshots;
    }

    private PresenceSnapshot read(UUID userId) {
        try {
            List<Object> values = redis.opsForHash().multiGet("pr:" + userId, List.of("state", "ver"));
            if (values.get(0) == null || values.get(1) == null) {
                return PresenceSnapshot.UNKNOWN;
            }
            return new PresenceSnapshot(PresenceStatus.valueOf((String) values.get(0)),
                    Long.parseLong((String) values.get(1)));
        } catch (RuntimeException e) {
            log.warn("presence snapshot read failed: user={}", userId, e);
            return PresenceSnapshot.UNKNOWN;
        }
    }
}
