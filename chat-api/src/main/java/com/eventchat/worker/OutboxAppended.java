package com.eventchat.worker;

/**
 * outbox에 행을 넣은 트랜잭션이 커밋되면 relay를 바로 깨우는 신호(폴링을 기다리지 않도록).
 */
public record OutboxAppended() {
}
