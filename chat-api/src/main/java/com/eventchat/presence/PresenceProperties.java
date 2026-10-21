package com.eventchat.presence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param grace             마지막 연결이 끊긴 뒤 OFFLINE으로 기록하기까지 기다리는 시간(그 안에 다시 붙으면 기록 없음)
 * @param nodeHeartbeat     노드 lease 갱신 주기
 * @param nodeLeaseTtl      노드 lease 수명. 이 시간 동안 갱신이 없으면 그 노드의 연결을 NODE_LOST로 해제한다
 * @param sweepInterval     유예가 끝난 사용자와 lease를 잃은 노드를 정리하는 주기
 * @param reconcileInterval DB 기록과 실제 연결 상태를 맞추는 주기
 */
@ConfigurationProperties("chat.presence")
public record PresenceProperties(Duration grace, Duration nodeHeartbeat, Duration nodeLeaseTtl,
        Duration sweepInterval, Duration reconcileInterval) {
}
