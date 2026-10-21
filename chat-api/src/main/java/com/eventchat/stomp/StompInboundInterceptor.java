package com.eventchat.stomp;

import com.eventchat.domain.user.Role;
import com.eventchat.realtime.RealtimeBus;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.stereotype.Component;

/**
 * clientInboundChannel의 첫 인터셉터.
 * <ul>
 *   <li>CONNECT: 접근 토큰 검증, heart-beat 요구, ADMIN 거부, 사용자별 연결 수</li>
 *   <li>SUBSCRIBE: 허용 목록, 세션 구독 인가. 거부는 연결을 유지한 채 SUBSCRIBE_REJECTED로 알린다</li>
 *   <li>SEND: 허용 목록(/app/sessions/{id}/events만)</li>
 * </ul>
 * 허용 목록 밖 목적지와 인증 실패는 ERROR로 연결을 닫는다.
 * 예외를 던지지 않고 직접 ERROR를 보낸다: 수신 순서 보장(preserveReceiveOrder)을 켜면 인바운드 예외가
 * 로그만 남고 오류 처리기까지 가지 않아, 클라이언트가 ERROR를 받지 못한 채 연결이 남는다.
 */
@Component
public class StompInboundInterceptor implements ExecutorChannelInterceptor {

    private static final String RESUME_HEADER = "resume-after-seq";

    private final JwtDecoder jwtDecoder;
    private final StompConnections connections;
    private final SessionSubscriptions subscriptions;
    private final StompDelivery delivery;
    private final RealtimeBus bus;
    private final StompProperties properties;

    public StompInboundInterceptor(JwtDecoder jwtDecoder, StompConnections connections,
            SessionSubscriptions subscriptions, StompDelivery delivery, RealtimeBus bus, StompProperties properties) {
        this.jwtDecoder = jwtDecoder;
        this.connections = connections;
        this.subscriptions = subscriptions;
        this.delivery = delivery;
        this.bus = bus;
        this.properties = properties;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        try {
            return check(message, accessor);
        } catch (StompRejectedException e) {
            delivery.closeWithError(accessor.getSessionId(), e.code(), e.getMessage());
            return null;
        }
    }

    private Message<?> check(Message<?> message, StompHeaderAccessor accessor) {
        return switch (accessor.getCommand()) {
            case CONNECT, STOMP -> {
                connect(accessor);
                yield message;
            }
            case SUBSCRIBE -> subscribe(message, accessor);
            case SEND -> {
                principal(accessor);
                if (!StompDestinations.SESSION_EVENTS.matcher(String.valueOf(accessor.getDestination())).matches()) {
                    throw forbidden(accessor.getDestination());
                }
                yield message;
            }
            case UNSUBSCRIBE -> {
                UUID sessionId = connections.unsubscribed(accessor.getSessionId(), accessor.getSubscriptionId());
                if (sessionId != null) {
                    bus.onLocalUnsubscribe(sessionId);
                }
                yield message;
            }
            default -> message;
        };
    }

    /** SimpleBroker가 구독을 등록한 뒤에 resume를 보낸다(그래야 그 사이의 라이브 이벤트가 빠지지 않는다). */
    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
        if (ex != null || !(handler instanceof SimpleBrokerMessageHandler)) {
            return;
        }
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return;
        }
        Matcher topic = StompDestinations.SESSION_TOPIC.matcher(String.valueOf(accessor.getDestination()));
        String resumeAfter = accessor.getFirstNativeHeader(RESUME_HEADER);
        if (!topic.matches() || resumeAfter == null) {
            return;
        }
        long afterSeq;
        try {
            afterSeq = Math.max(0, Long.parseLong(resumeAfter.trim()));
        } catch (NumberFormatException e) {
            return;
        }
        UUID sessionId = UUID.fromString(topic.group(1));
        bus.awaitReady(sessionId);
        subscriptions.resume(accessor.getSessionId(), accessor.getSubscriptionId(), sessionId, afterSeq);
    }

    private void connect(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new StompRejectedException("AUTH_INVALID", "Authorization: Bearer 헤더가 필요합니다.");
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(authorization.substring(7).trim());
        } catch (JwtException e) {
            boolean expired = e instanceof JwtValidationException invalid && invalid.getErrors().stream()
                    .anyMatch(error -> String.valueOf(error.getDescription()).contains("expired"));
            throw new StompRejectedException(expired ? "AUTH_TOKEN_EXPIRED" : "AUTH_INVALID",
                    expired ? "접근 토큰이 만료되었습니다." : "접근 토큰이 유효하지 않습니다.");
        }
        String role = jwt.getClaimAsString("role");
        if (Role.ADMIN.name().equals(role)) {
            throw new StompRejectedException("ADMIN_READ_ONLY", "관리자는 실시간 채널에 연결할 수 없습니다.");
        }
        checkHeartbeat(accessor.getHeartbeat());

        StompPrincipal principal = new StompPrincipal(UUID.fromString(jwt.getSubject()), role, jwt.getExpiresAt());
        if (!connections.register(accessor.getSessionId(), principal)) {
            throw new StompRejectedException("TOO_MANY_CONNECTIONS",
                    "동시에 연결할 수 있는 수(" + properties.maxConnectionsPerUserPerNode() + ")를 넘었습니다.");
        }
        accessor.setUser(principal);
    }

    /**
     * heart-beat: cx(클라이언트가 보내는 주기), cy(클라이언트가 받길 원하는 주기).
     * 연결끊김을 서버가 판단하려면 양쪽 모두 켜져 있어야 한다.
     */
    private void checkHeartbeat(long[] heartbeat) {
        long max = properties.maxHeartbeat().toMillis();
        if (heartbeat == null || heartbeat.length != 2
                || heartbeat[0] <= 0 || heartbeat[0] > max || heartbeat[1] <= 0 || heartbeat[1] > max) {
            throw new StompRejectedException("HEARTBEAT_REQUIRED", "heart-beat를 지정해야 합니다(예: "
                    + properties.clientHeartbeat().toMillis() + "," + properties.serverHeartbeat().toMillis() + ").");
        }
    }

    private Message<?> subscribe(Message<?> message, StompHeaderAccessor accessor) {
        StompPrincipal principal = principal(accessor);
        String destination = String.valueOf(accessor.getDestination());
        if (destination.equals(StompDestinations.USER_RECEIPTS) || destination.equals(StompDestinations.USER_SYSTEM)) {
            return message;
        }
        Matcher topic = StompDestinations.SESSION_TOPIC.matcher(destination);
        if (!topic.matches()) {
            throw forbidden(destination);
        }
        UUID sessionId = UUID.fromString(topic.group(1));
        Optional<Map<String, Object>> rejection = subscriptions.rejection(sessionId, principal.userId());
        if (rejection.isPresent()) {
            // ERROR를 보내면 다른 구독까지 끊기고 자동 재연결과 맞물려 루프가 생긴다. 프레임만 버리고 알린다.
            delivery.toConnection(principal.getName(), accessor.getSessionId(), StompDestinations.RECEIPTS,
                    rejection.get());
            return null;
        }
        connections.subscribed(accessor.getSessionId(), accessor.getSubscriptionId(), sessionId);
        bus.onLocalSubscribe(sessionId);
        return message;
    }

    private static StompPrincipal principal(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof StompPrincipal principal) {
            return principal;
        }
        throw new StompRejectedException("AUTH_INVALID", "CONNECT로 인증한 뒤에 보낼 수 있습니다.");
    }

    private static StompRejectedException forbidden(String destination) {
        return new StompRejectedException("FORBIDDEN_DESTINATION", "허용하지 않는 목적지입니다: " + destination);
    }
}
