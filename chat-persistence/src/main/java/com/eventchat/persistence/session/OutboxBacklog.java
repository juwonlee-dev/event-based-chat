package com.eventchat.persistence.session;

import java.time.Instant;

/** @param oldestCreatedAt 가장 오래된 행의 생성 시각. 비어 있으면 null */
public record OutboxBacklog(long pending, Instant oldestCreatedAt) {
}
