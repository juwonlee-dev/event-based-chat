package com.eventchat.domain.session;

/**
 * 사용자의 연결 상태. 탭이나 기기 중 하나라도 연결되어 있으면 ONLINE이다.
 */
public enum PresenceStatus {
    ONLINE,
    OFFLINE
}
