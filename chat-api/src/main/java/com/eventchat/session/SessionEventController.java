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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions/{sessionId}/events")
@RequiredArgsConstructor
public class SessionEventController {

    /** 클라이언트가 보낼 수 있는 타입. 나머지는 서버가 만든다. */
    private static final Set<EventType> CLIENT_TYPES =
            Set.of(EventType.MESSAGE, EventType.TYPING_STARTED, EventType.TYPING_STOPPED, EventType.READ_CURSOR);
    private static final int CLIENT_EVENT_ID_MAX_LENGTH = 64;

    private final SessionWriter writer;
    private final MessageProperties messageProperties;
    private final IngestProperties ingestProperties;

    /**
     * 이벤트 수집.
     * <ul>
     *   <li>MESSAGE: 201(저장) / 200(같은 clientEventId 재전송, 처음 결과)</li>
     *   <li>TYPING_STARTED·TYPING_STOPPED: 201(저장) / 204(같은 상태의 반복이라 저장 안 함)</li>
     *   <li>READ_CURSOR: 202. 저장했으면 이벤트를 본문에 싣는다</li>
     * </ul>
     */
    @PostMapping
    public ResponseEntity<EventView> ingest(@PathVariable UUID sessionId, @RequestBody IngestRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        return switch (eventType(request.type())) {
            case MESSAGE -> {
                IngestResult result = writer.message(sessionId, actorId, clientEventId(request.clientEventId()),
                        content(payload), request.clientTime());
                yield ResponseEntity.status(result.status() == IngestResult.Status.STORED ? HttpStatus.CREATED : HttpStatus.OK)
                        .body(view(result));
            }
            case TYPING_STARTED, TYPING_STOPPED -> {
                IngestResult result = writer.typing(sessionId, actorId,
                        eventType(request.type()) == EventType.TYPING_STARTED);
                yield result.status() == IngestResult.Status.STORED
                        ? ResponseEntity.status(HttpStatus.CREATED).body(view(result))
                        : ResponseEntity.noContent().build();
            }
            case READ_CURSOR -> {
                IngestResult result = writer.readCursor(sessionId, actorId, upToSeq(payload));
                yield ResponseEntity.status(HttpStatus.ACCEPTED).body(result.event() == null ? null : view(result));
            }
            default -> throw new IllegalStateException();
        };
    }

    private EventView view(IngestResult result) {
        return EventView.of(SessionEventRow.of(result.event()), ingestProperties.typingTtl());
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
}
