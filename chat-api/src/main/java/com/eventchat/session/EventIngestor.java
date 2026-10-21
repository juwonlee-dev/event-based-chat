package com.eventchat.session;

import com.eventchat.domain.session.EventType;
import com.eventchat.persistence.session.SessionEventRow;
import com.eventchat.session.SessionWriter.IngestResult;
import com.eventchat.web.MessageProperties;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 이벤트 수집 요청을 검증해 쓰기 경로로 넘긴다. REST와 STOMP SEND가 같은 처리기를 쓴다.
 */
@Service
@RequiredArgsConstructor
public class EventIngestor {

    /** 클라이언트가 보낼 수 있는 타입. 나머지는 서버가 만든다. */
    private static final Set<EventType> CLIENT_TYPES =
            Set.of(EventType.MESSAGE, EventType.TYPING_STARTED, EventType.TYPING_STOPPED, EventType.READ_CURSOR);
    private static final int CLIENT_EVENT_ID_MAX_LENGTH = 64;

    private final SessionWriter writer;
    private final MessageProperties messageProperties;
    private final IngestProperties ingestProperties;

    public Ingested ingest(UUID sessionId, UUID actorId, IngestRequest request) {
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        EventType type = eventType(request.type());
        IngestResult result = switch (type) {
            case MESSAGE -> writer.message(sessionId, actorId, clientEventId(request.clientEventId()),
                    content(payload), request.clientTime());
            case TYPING_STARTED, TYPING_STOPPED -> writer.typing(sessionId, actorId, type == EventType.TYPING_STARTED);
            case READ_CURSOR -> writer.readCursor(sessionId, actorId, upToSeq(payload));
            default -> throw new IllegalStateException();
        };
        EventView view = result.event() == null
                ? null
                : EventView.of(SessionEventRow.of(result.event()), ingestProperties.typingTtl());
        return new Ingested(type, result.status(), view);
    }

    private static EventType eventType(String type) {
        try {
            EventType parsed = EventType.valueOf(String.valueOf(type));
            if (CLIENT_TYPES.contains(parsed)) {
                return parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 같은 오류로 돌려준다
        }
        throw new ApiException(ErrorCode.EVENT_TYPE_NOT_ALLOWED,
                "보낼 수 있는 이벤트 타입은 " + CLIENT_TYPES.stream().map(Enum::name).sorted().toList() + "입니다.");
    }

    private static String clientEventId(String clientEventId) {
        if (clientEventId == null || clientEventId.isBlank() || clientEventId.length() > CLIENT_EVENT_ID_MAX_LENGTH) {
            throw ApiException.invalid("clientEventId", "1~" + CLIENT_EVENT_ID_MAX_LENGTH + "자여야 합니다.");
        }
        return clientEventId;
    }

    /** 앞뒤 공백을 뺀 뒤 1자 이상, 최대 길이(유니코드 문자 수) 이하. */
    private String content(Map<String, Object> payload) {
        if (!(payload.get("content") instanceof String raw) || raw.isBlank()) {
            throw ApiException.invalid("payload.content", "내용을 입력해 주세요.");
        }
        String content = raw.strip();
        int length = content.codePointCount(0, content.length());
        int maxLength = messageProperties.maxLength();
        if (length > maxLength) {
            throw new ApiException(ErrorCode.MESSAGE_TOO_LONG, "메시지는 " + maxLength + "자까지 보낼 수 있습니다.",
                    Map.of("maxLength", maxLength, "actualLength", length), null);
        }
        return content;
    }

    private static long upToSeq(Map<String, Object> payload) {
        if (payload.get("upToSeq") instanceof Number number && number.doubleValue() == number.longValue()
                && number.longValue() >= 0) {
            return number.longValue();
        }
        throw ApiException.invalid("payload.upToSeq", "0 이상의 정수여야 합니다.");
    }

    public record IngestRequest(String clientEventId, String type, Map<String, Object> payload, Instant clientTime) {
    }

    /** @param event 저장했거나 처음 저장된 이벤트. 저장하지 않았으면 null */
    public record Ingested(EventType type, IngestResult.Status status, EventView event) {
    }
}
