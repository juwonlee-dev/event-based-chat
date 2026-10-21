package com.eventchat.session;

import com.eventchat.domain.session.EventType;
import com.eventchat.domain.user.Role;
import com.eventchat.persistence.session.SessionEventSearchRepository;
import com.eventchat.session.EventIngestor.IngestRequest;
import com.eventchat.session.EventIngestor.Ingested;
import com.eventchat.session.SessionQueryService.EventPage;
import com.eventchat.session.SessionWriter.IngestResult;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions/{sessionId}/events")
@RequiredArgsConstructor
public class SessionEventController {

    private static final int PARTICIPANT_MAX_LIMIT = 200;
    private static final int ADMIN_MAX_LIMIT = 500;

    private final EventIngestor ingestor;
    private final SessionQueryService queries;

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
        Ingested ingested = ingestor.ingest(sessionId, UUID.fromString(jwt.getSubject()), request);
        boolean stored = ingested.status() == IngestResult.Status.STORED;
        return switch (ingested.type()) {
            case MESSAGE -> ResponseEntity.status(stored ? HttpStatus.CREATED : HttpStatus.OK).body(ingested.event());
            case READ_CURSOR -> ResponseEntity.status(HttpStatus.ACCEPTED).body(ingested.event());
            default -> stored
                    ? ResponseEntity.status(HttpStatus.CREATED).body(ingested.event())
                    : ResponseEntity.noContent().build();
        };
    }

    /**
     * 이벤트 조회(seq 오름차순, afterSeq로 이어 읽기).
     * <ul>
     *   <li>참여자: 실시간 채널에서 빠진 구간을 보충할 때 afterSeq만 쓴다(최대 200건).</li>
     *   <li>관리자: 디버깅·검증용으로 from·to(ISO-8601, 양 끝 포함, server_ts 기준)와 types를 더 쓸 수 있고,
     *       clientTime·schemaVersion까지 받는다(최대 500건).</li>
     * </ul>
     */
    @GetMapping
    public EventPage events(@PathVariable UUID sessionId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Set<EventType> types,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "100") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        boolean admin = Role.ADMIN.name().equals(jwt.getClaimAsString("role"));
        if (!admin && (from != null || to != null || types != null)) {
            throw new ApiException(ErrorCode.ADMIN_ONLY, "from·to·types 조건은 관리자만 쓸 수 있습니다.");
        }
        int maxLimit = admin ? ADMIN_MAX_LIMIT : PARTICIPANT_MAX_LIMIT;
        if (limit < 1 || limit > maxLimit) {
            throw ApiException.invalid("limit", "1~" + maxLimit + " 사이여야 합니다.");
        }
        if (afterSeq < 0) {
            throw ApiException.invalid("afterSeq", "0 이상이어야 합니다.");
        }
        Instant fromTime = time("from", from);
        Instant toTime = time("to", to);
        if (fromTime != null && toTime != null && fromTime.isAfter(toTime)) {
            throw ApiException.invalid("from", "to보다 늦을 수 없습니다.");
        }
        // to는 그 밀리초 끝까지 포함한다(응답의 serverTime을 그대로 넣어도 그 이벤트가 빠지지 않도록).
        Instant before = toTime == null ? null : toTime.plusMillis(1);
        return queries.events(sessionId, UUID.fromString(jwt.getSubject()), admin,
                new SessionEventSearchRepository.Range(sessionId, afterSeq, fromTime, before, types), limit);
    }

    /** 응답 시각과 같은 밀리초 단위로 해석한다. */
    private static Instant time(String field, String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.INVALID_TIME_FORMAT,
                    field + "는 ISO-8601 형식(예: 2026-10-21T03:12:45.123Z)이어야 합니다.");
        }
    }

}
