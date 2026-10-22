package com.eventchat.stomp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.OrderedMessageChannelDecorator;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이 노드에 붙은 STOMP 클라이언트로 보내는 경로를 한곳에 모은다.
 * <ul>
 *   <li>세션 topic: 로컬 SimpleBroker를 거쳐 그 세션의 로컬 구독자 전원에게</li>
 *   <li>특정 구독: resume처럼 한 구독에만 보낼 때 clientOutboundChannel로 바로</li>
 *   <li>특정 연결: /user/queue/receipts 회신(보낸 연결에만)</li>
 * </ul>
 */
@Component
public class StompDelivery {

    private static final Log log = LogFactory.getLog(StompDelivery.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final MessageChannel clientOutboundChannel;
    private final SimpUserRegistry userRegistry;
    private final JsonMapper jsonMapper;

    public StompDelivery(@Lazy SimpMessagingTemplate messagingTemplate,
            @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel,
            @Lazy SimpUserRegistry userRegistry, JsonMapper jsonMapper) {
        this.messagingTemplate = messagingTemplate;
        this.clientOutboundChannel = clientOutboundChannel;
        this.userRegistry = userRegistry;
        this.jsonMapper = jsonMapper;
    }

    public byte[] json(Object value) {
        return jsonMapper.writeValueAsBytes(value);
    }

    /** 이미 직렬화한 JSON(예: 다른 노드에서 Redis로 받은 envelope)을 그대로 topic으로 보낸다. */
    public void toTopic(UUID sessionId, byte[] json) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        accessor.setLeaveMutable(true);
        messagingTemplate.send(StompDestinations.sessionTopic(sessionId),
                MessageBuilder.createMessage(json, accessor.getMessageHeaders()));
    }

    public void toTopic(UUID sessionId, Object payload) {
        toTopic(sessionId, json(payload));
    }

    /**
     * 한 구독에만 보낸다. 같은 세션의 다른 구독자에게는 가지 않는다.
     * clientOutboundChannel은 스레드 풀이라 그냥 보내면 같은 연결에서도 순서가 바뀔 수 있다.
     * 묶음을 브로커와 같은 순서 보장 장치로 하나씩 내보낸다(resume 이벤트 → RESUMED 순서).
     */
    public void toSubscription(String simpSessionId, String subscriptionId, UUID sessionId, List<byte[]> frames) {
        MessageChannel ordered = new OrderedMessageChannelDecorator(clientOutboundChannel, log);
        for (byte[] json : frames) {
            SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            accessor.setSessionId(simpSessionId);
            accessor.setSubscriptionId(subscriptionId);
            accessor.setDestination(StompDestinations.sessionTopic(sessionId));
            accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
            accessor.setLeaveMutable(true);
            ordered.send(MessageBuilder.createMessage(json, accessor.getMessageHeaders()));
        }
    }

    /** /user/{user}{queue} 중 simpSessionId 연결에만 보낸다(broadcast=false와 같다). */
    public void toConnection(String user, String simpSessionId, String queue, Object payload) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(simpSessionId);
        accessor.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(user, queue, payload, accessor.getMessageHeaders());
    }

    /** 이 노드에 붙은 모든 사용자에게(저하·복구 알림). */
    public void toAllUsers(String queue, Object payload) {
        userRegistry.getUsers().forEach(user -> messagingTemplate.convertAndSendToUser(user.getName(), queue, payload));
    }

    /** 이 노드에 로컬 구독자가 있는 세션들. */
    public Set<UUID> locallySubscribedSessions() {
        return userRegistry.findSubscriptions(s -> s.getDestination() != null
                        && StompDestinations.SESSION_TOPIC.matcher(s.getDestination()).matches())
                .stream()
                .map(s -> {
                    Matcher matcher = StompDestinations.SESSION_TOPIC.matcher(s.getDestination());
                    matcher.matches();
                    return UUID.fromString(matcher.group(1));
                })
                .collect(Collectors.toSet());
    }

    /** STOMP ERROR를 보내면 Spring이 그 연결을 닫는다. */
    public void closeWithError(String simpSessionId, String code, String detail) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(code);
        accessor.setSessionId(simpSessionId);
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        accessor.setLeaveMutable(true);
        clientOutboundChannel.send(MessageBuilder.createMessage(errorBody(code, detail), accessor.getMessageHeaders()));
    }

    byte[] errorBody(String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("detail", detail);
        return json(body);
    }
}
