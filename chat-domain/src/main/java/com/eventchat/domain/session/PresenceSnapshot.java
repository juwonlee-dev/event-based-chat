package com.eventchat.domain.session;

/**
 * 어느 시점의 연결 상태와 그 버전. 버전은 Redis가 사용자별로 단조 증가하게 붙인다(기록이 없으면 OFFLINE, 0).
 */
public record PresenceSnapshot(PresenceStatus status, long ver) {

    public static final PresenceSnapshot UNKNOWN = new PresenceSnapshot(PresenceStatus.OFFLINE, 0);
}
