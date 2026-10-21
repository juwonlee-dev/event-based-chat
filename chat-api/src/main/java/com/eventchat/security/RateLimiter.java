package com.eventchat.security;

import com.eventchat.web.error.ApiException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 고정 창 레이트 리밋. 키를 처음 만들 때 TTL을 함께 걸어(SET NX EX) TTL 없는 키가 남지 않게 한다.
 * Redis 장애 시: 로그인처럼 막아야 하는 것은 노드 로컬 창으로, 나머지는 통과시킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final int LOCAL_WINDOW_LIMIT = 10_000;

    private final StringRedisTemplate redis;
    private final Map<String, LocalWindow> localWindows = new ConcurrentHashMap<>();

    public void check(String key, int limit, Duration window, boolean localFallback) {
        long count;
        try {
            String redisKey = "rl:" + key;
            redis.opsForValue().setIfAbsent(redisKey, "0", window);
            Long incremented = redis.opsForValue().increment(redisKey);
            count = incremented == null ? 0 : incremented;
        } catch (RuntimeException e) {
            log.warn("rate limiter falls back (redis unavailable): {}", e.getMessage());
            if (!localFallback) {
                return;
            }
            count = incrementLocal(key, window);
        }
        if (count > limit) {
            throw ApiException.rateLimited(window);
        }
    }

    private long incrementLocal(String key, Duration window) {
        long now = System.currentTimeMillis();
        if (localWindows.size() > LOCAL_WINDOW_LIMIT) {
            localWindows.values().removeIf(w -> w.resetAt() <= now);
        }
        return localWindows.compute(key, (k, w) -> w == null || w.resetAt() <= now
                ? new LocalWindow(1, now + window.toMillis())
                : new LocalWindow(w.count() + 1, w.resetAt())).count();
    }

    private record LocalWindow(long count, long resetAt) {
    }
}
