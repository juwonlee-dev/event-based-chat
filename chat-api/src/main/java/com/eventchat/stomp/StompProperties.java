package com.eventchat.stomp;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * @param serverHeartbeat              서버 → 클라이언트 heartbeat 주기
 * @param clientHeartbeat              클라이언트 → 서버 heartbeat 요구 주기
 * @param maxHeartbeat                 CONNECT에서 이보다 긴 heartbeat(또는 0)를 요청하면 거부한다
 * @param maxConnectionsPerUserPerNode 노드 메모리로 센다(Redis 장애나 죽은 노드의 연결 때문에 잘못 거부하지 않도록)
 * @param messageSizeLimit             수신 프레임 한도. 2000자 메시지보다 넉넉히 둬서 긴 글도 연결을 끊지 않고 거부한다
 * @param sendBufferSizeLimit          연결별 송신 버퍼. resume 상한(resumeMaxBytes)이 이 안에 들어가야 한다
 * @param resumeMaxEvents              SUBSCRIBE resume로 보내는 최대 건수. 넘으면 SYSTEM_RESYNC로 보충 API를 쓰게 한다
 * @param resumeMaxConcurrent          노드에서 동시에 처리하는 resume 수. 넘치면 기다리지 않고 SYSTEM_RESYNC
 * @param drainWindow                  종료할 때 로컬 연결을 이 시간에 걸쳐 나눠 닫는다(재연결이 한꺼번에 몰리지 않도록)
 */
@ConfigurationProperties("chat.stomp")
public record StompProperties(Duration serverHeartbeat, Duration clientHeartbeat, Duration maxHeartbeat,
        int maxConnectionsPerUserPerNode, DataSize messageSizeLimit, DataSize sendBufferSizeLimit,
        Duration sendTimeLimit, Duration timeToFirstMessage, int resumeMaxEvents, DataSize resumeMaxBytes,
        int resumeMaxConcurrent, int inboundConcurrency, Duration drainWindow, List<String> allowedOrigins) {
}
