package com.eventchat.realtime;

import com.eventchat.stomp.StompConnections;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 노드 제어 채널 {@code chat:ctl}. 전체 로그아웃·계정 정지 때 REVOKE_USER를 보내면,
 * 그 사용자의 연결을 가진 노드가 ERROR AUTH_REVOKED로 닫는다.
 * Redis가 끊겨 있으면 즉시 종료만 늦어지고, 갱신 토큰이 폐기되었으므로 접근 토큰이 만료되면 반드시 끊긴다.
 */
@Slf4j
@Component
public class RealtimeControl implements MessageListener {

    static final String CHANNEL = "chat:ctl";
    private static final String REVOKE_USER = "REVOKE_USER";

    private final StringRedisTemplate redis;
    private final StompConnections connections;
    private final JsonMapper jsonMapper;

    public RealtimeControl(StringRedisTemplate redis, StompConnections connections, JsonMapper jsonMapper) {
        this.redis = redis;
        this.connections = connections;
        this.jsonMapper = jsonMapper;
    }

    public void revokeUser(UUID userId) {
        String message = jsonMapper.writeValueAsString(Map.of("type", REVOKE_USER, "userId", userId));
        try {
            redis.convertAndSend(CHANNEL, message);
        } catch (RuntimeException e) {
            log.warn("failed to publish {} for {}, closing local connections only", REVOKE_USER, userId, e);
            closeLocal(userId);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        JsonNode command = jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        if (REVOKE_USER.equals(command.path("type").asString())) {
            closeLocal(UUID.fromString(command.path("userId").asString()));
        }
    }

    private void closeLocal(UUID userId) {
        int closed = connections.closeUser(userId, "AUTH_REVOKED", "로그아웃되었습니다. 다시 로그인해 주세요.");
        if (closed > 0) {
            log.info("closed {} connections of {} ({})", closed, userId, REVOKE_USER);
        }
    }
}
