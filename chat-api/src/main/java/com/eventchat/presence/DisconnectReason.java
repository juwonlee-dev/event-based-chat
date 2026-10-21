package com.eventchat.presence;

/**
 * 연결이 끊긴 이유. DISCONNECT payload의 reason에 표시용으로 남는다(RECONCILED는 대조 작업이 남긴다).
 */
public enum DisconnectReason {
    /** 소켓이 닫혔다(클라이언트 종료, 토큰 만료·강제 종료, 드레이닝). */
    SOCKET_CLOSED,
    /** STOMP heart-beat가 끊겨 서버가 닫았다(프로세스 정지, 망 단절). */
    HEARTBEAT_TIMEOUT,
    /** 연결을 들고 있던 노드의 lease가 만료됐다. */
    NODE_LOST
}
