package com.eventchat.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param healthCheckInterval  Redis 중계 상태를 확인하는 주기
 * @param degradedPollInterval 저하 상태에서 클라이언트가 보충 API를 부르는 주기(SYSTEM_DEGRADED에 실린다)
 */
@ConfigurationProperties("chat.realtime")
public record RealtimeProperties(Duration healthCheckInterval, Duration degradedPollInterval) {
}
