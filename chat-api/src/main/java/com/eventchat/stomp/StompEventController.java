package com.eventchat.stomp;

import com.eventchat.domain.session.EventType;
import com.eventchat.session.EventIngestor;
import com.eventchat.session.EventIngestor.IngestRequest;
import com.eventchat.session.EventIngestor.Ingested;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import java.security.Principal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * STOMP SEND /app/sessions/{id}/events. 본문과 처리기는 REST 수집 API와 같고, 결과는 보낸 연결에만 회신한다.
 * 거부해도 연결은 유지한다(EVENT_RESULT{REJECTED}).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class StompEventController {

    private static final Duration LOCK_RETRY_AFTER = Duration.ofSeconds(1);

    private final EventIngestor ingestor;
    private final JsonMapper jsonMapper;

    @MessageMapping("/sessions/{sessionId}/events")
    @SendToUser(destinations = StompDestinations.RECEIPTS, broadcast = false)
    public EventResult ingest(@DestinationVariable UUID sessionId, @Payload IngestRequest request, Principal principal) {
        StompPrincipal user = (StompPrincipal) principal;
        Ingested ingested = ingestor.ingest(sessionId, user.userId(), request);
        if (ingested.type() != EventType.MESSAGE) {
            return null;
        }
        return EventResult.of(sessionId, request.clientEventId(), ingested);
    }

    @MessageExceptionHandler
    @SendToUser(destinations = StompDestinations.RECEIPTS, broadcast = false)
    public EventResult rejected(Exception e, Message<?> message) {
        return EventResult.rejected(sessionId(message.getHeaders()), clientEventId(message), error(e));
    }

    private Map<String, Object> error(Exception e) {
        Map<String, Object> error = new LinkedHashMap<>();
        if (e instanceof ApiException api) {
            error.put("status", api.getCode().status().value());
            error.put("code", api.getCode().name());
            error.putAll(api.getProperties());
        } else if (e instanceof PessimisticLockingFailureException) {
            error.put("status", ErrorCode.TEMPORARILY_UNAVAILABLE.status().value());
            error.put("code", ErrorCode.TEMPORARILY_UNAVAILABLE.name());
            error.put("retryAfterMs", LOCK_RETRY_AFTER.toMillis());
        } else if (e instanceof MessageConversionException) {
            error.put("status", ErrorCode.VALIDATION_FAILED.status().value());
            error.put("code", ErrorCode.VALIDATION_FAILED.name());
        } else {
            log.warn("STOMP SEND failed", e);
            error.put("status", 500);
            error.put("code", "INTERNAL_ERROR");
        }
        return error;
    }

    private static UUID sessionId(MessageHeaders headers) {
        String destination = SimpMessageHeaderAccessor.getDestination(headers);
        Matcher matcher = StompDestinations.SESSION_EVENTS.matcher(String.valueOf(destination));
        return matcher.matches() ? UUID.fromString(matcher.group(1)) : null;
    }

    /** 본문을 해석하지 못했으면 null로 회신한다. */
    private String clientEventId(Message<?> message) {
        if (!(message.getPayload() instanceof byte[] body)) {
            return null;
        }
        try {
            JsonNode clientEventId = jsonMapper.readTree(body).get("clientEventId");
            return clientEventId == null || !clientEventId.isString() ? null : clientEventId.asString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
