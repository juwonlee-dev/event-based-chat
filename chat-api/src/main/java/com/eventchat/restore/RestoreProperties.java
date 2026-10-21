package com.eventchat.restore;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param timeout          timeline 복원 전체 마감. 넘기면 503 RESTORE_TIMEOUT
 * @param defaultItemLimit timeline 화면 항목 기본 개수
 * @param maxItemLimit     timeline 화면 항목 최대 개수
 * @param futureTolerance  이 범위 안의 미래 시각은 현재로 맞추고, 더 먼 미래는 400 AT_IN_FUTURE
 */
@ConfigurationProperties("chat.restore")
public record RestoreProperties(Duration timeout, int defaultItemLimit, int maxItemLimit, Duration futureTolerance) {
}
