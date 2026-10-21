package com.eventchat.stomp;

import com.eventchat.presence.DisconnectReason;
import com.eventchat.presence.PresenceTracker;
import com.eventchat.realtime.RealtimeBus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * 이 노드의 STOMP 연결 목록.
 * <ul>
 *   <li>사용자별 동시 연결 수 제한(노드 메모리로 센다)</li>
 *   <li>접근 토큰 만료 시각에 ERROR AUTH_TOKEN_EXPIRED로 닫기</li>
 *   <li>강제 종료(AUTH_REVOKED), 종료 시 드레이닝</li>
 *   <li>연결 상태(presence) 등록·해제</li>
 * </ul>
 */
@Component
public class StompConnections {

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> byUser = new ConcurrentHashMap<>();
    /** 드레이닝 때 1012로 닫으려고 원래 WebSocket 세션을 따로 들고 있는다. */
    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    /** 브로커가 heart-beat 누락으로 닫은 연결. 끊긴 이유를 구분하려고 표시해 둔다. */
    private final Set<String> heartbeatTimeouts = ConcurrentHashMap.newKeySet();
    private final StompProperties properties;
    private final TaskScheduler scheduler;
    private final StompDelivery delivery;
    private final RealtimeBus bus;
    private final PresenceTracker presence;
    private final MeterRegistry meters;

    public StompConnections(StompProperties properties,
            @Qualifier("stompTaskScheduler") TaskScheduler stompTaskScheduler, StompDelivery delivery, RealtimeBus bus,
            PresenceTracker presence, MeterRegistry meters) {
        this.properties = properties;
        this.scheduler = stompTaskScheduler;
        this.delivery = delivery;
        this.bus = bus;
        this.presence = presence;
        this.meters = meters;
        Gauge.builder("chat.ws.connections", connections, Map::size)
                .description("이 노드에서 CONNECT를 마친 STOMP 연결 수")
                .register(meters);
    }

    /** CONNECT 수락. 사용자의 연결이 이미 상한이면 false. */
    boolean register(String simpSessionId, StompPrincipal principal) {
        boolean[] accepted = {false};
        byUser.compute(principal.userId(), (userId, sessions) -> {
            Set<String> current = sessions == null ? ConcurrentHashMap.newKeySet() : sessions;
            if (current.size() < properties.maxConnectionsPerUserPerNode()) {
                accepted[0] = current.add(simpSessionId);
            }
            return current.isEmpty() ? null : current;
        });
        if (!accepted[0]) {
            return false;
        }
        presence.connected(principal.userId(), simpSessionId);
        // 토큰이 만료되면 연결을 닫는다. 클라이언트는 그 전에 갱신한 토큰으로 다시 연결한다.
        ScheduledFuture<?> expiry = scheduler.schedule(
                () -> delivery.closeWithError(simpSessionId, "AUTH_TOKEN_EXPIRED", "접근 토큰이 만료되었습니다."),
                principal.tokenExpiresAt());
        connections.put(simpSessionId, new Connection(principal, expiry, new ConcurrentHashMap<>()));
        if (!sockets.containsKey(simpSessionId)) {
            // CONNECT를 처리하는 사이에 소켓이 닫혔다: 종료 처리가 이 연결을 못 보고 지나갔을 수 있다.
            release(simpSessionId);
        }
        return true;
    }

    void heartbeatTimedOut(String simpSessionId) {
        heartbeatTimeouts.add(simpSessionId);
    }

    void subscribed(String simpSessionId, String subscriptionId, UUID sessionId) {
        Connection connection = connections.get(simpSessionId);
        if (connection != null) {
            connection.subscriptions().put(subscriptionId, sessionId);
        }
    }

    UUID unsubscribed(String simpSessionId, String subscriptionId) {
        Connection connection = connections.get(simpSessionId);
        return connection == null ? null : connection.subscriptions().remove(subscriptionId);
    }

    /** 이 사용자의 이 노드 연결을 모두 닫는다(전체 로그아웃·계정 정지). */
    public int closeUser(UUID userId, String code, String detail) {
        Set<String> sessions = byUser.getOrDefault(userId, Set.of());
        List.copyOf(sessions).forEach(id -> delivery.closeWithError(id, code, detail));
        return sessions.size();
    }

    List<WebSocketSession> openSockets() {
        return List.copyOf(sockets.values());
    }

    @EventListener
    void onDisconnect(SessionDisconnectEvent event) {
        release(event.getSessionId());
    }

    /** 연결 정리. 목록에서 꺼낸 쪽만 정리하므로 두 번 불려도 한 번만 처리한다. */
    private void release(String simpSessionId) {
        boolean heartbeatTimeout = heartbeatTimeouts.remove(simpSessionId);
        Connection connection = connections.remove(simpSessionId);
        if (connection == null) {
            return;
        }
        connection.expiry().cancel(false);
        connection.subscriptions().values().forEach(bus::onLocalUnsubscribe);
        UUID userId = connection.principal().userId();
        byUser.computeIfPresent(userId, (id, sessions) -> {
            sessions.remove(simpSessionId);
            return sessions.isEmpty() ? null : sessions;
        });
        DisconnectReason reason = heartbeatTimeout ? DisconnectReason.HEARTBEAT_TIMEOUT : DisconnectReason.SOCKET_CLOSED;
        meters.counter("chat.ws.disconnects", "reason", reason.name()).increment();
        presence.disconnected(userId, simpSessionId, reason);
    }

    WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sockets.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sockets.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    private record Connection(StompPrincipal principal, ScheduledFuture<?> expiry, Map<String, UUID> subscriptions) {
    }
}
