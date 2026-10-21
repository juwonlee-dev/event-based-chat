package com.eventchat.stomp;

import com.eventchat.realtime.RealtimeBus;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * 이 노드의 STOMP 연결 목록.
 * <ul>
 *   <li>사용자별 동시 연결 수 제한(노드 메모리로 센다)</li>
 *   <li>접근 토큰 만료 시각에 ERROR AUTH_TOKEN_EXPIRED로 닫기</li>
 * </ul>
 */
@Component
public class StompConnections {

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> byUser = new ConcurrentHashMap<>();
    private final StompProperties properties;
    private final TaskScheduler scheduler;
    private final StompDelivery delivery;
    private final RealtimeBus bus;

    public StompConnections(StompProperties properties,
            @Qualifier("stompTaskScheduler") TaskScheduler stompTaskScheduler, StompDelivery delivery, RealtimeBus bus) {
        this.properties = properties;
        this.scheduler = stompTaskScheduler;
        this.delivery = delivery;
        this.bus = bus;
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
        // 토큰이 만료되면 연결을 닫는다. 클라이언트는 그 전에 갱신한 토큰으로 다시 연결한다.
        ScheduledFuture<?> expiry = scheduler.schedule(
                () -> delivery.closeWithError(simpSessionId, "AUTH_TOKEN_EXPIRED", "접근 토큰이 만료되었습니다."),
                principal.tokenExpiresAt());
        connections.put(simpSessionId, new Connection(principal, expiry, new ConcurrentHashMap<>()));
        return true;
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

    @EventListener
    void onDisconnect(SessionDisconnectEvent event) {
        Connection connection = connections.remove(event.getSessionId());
        if (connection == null) {
            return;
        }
        connection.expiry().cancel(false);
        connection.subscriptions().values().forEach(bus::onLocalUnsubscribe);
        byUser.computeIfPresent(connection.principal().userId(), (userId, sessions) -> {
            sessions.remove(event.getSessionId());
            return sessions.isEmpty() ? null : sessions;
        });
    }

    private record Connection(StompPrincipal principal, ScheduledFuture<?> expiry, Map<String, UUID> subscriptions) {
    }
}
