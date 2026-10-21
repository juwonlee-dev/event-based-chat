package com.eventchat.session;

import com.eventchat.domain.session.SessionStatus;
import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
import com.eventchat.session.SessionWriter.CommandType;
import com.eventchat.session.SessionWriter.CreateResult;
import com.eventchat.web.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;
    private static final int MAX_LIMIT = 100;
    /** 짧은 검색어는 일치하는 행이 너무 많아 정렬 비용이 커진다(trigram도 3자부터 가장 효과적). */
    private static final int TITLE_SEARCH_MIN_LENGTH = 2;
    private static final int TITLE_MAX_LENGTH = 100;

    private final SessionWriter writer;
    private final SessionQueryService queries;
    private final ReadPermits readPermits;

    /** 같은 Idempotency-Key로 다시 보내면 처음 만든 세션을 200으로 돌려준다. */
    @PostMapping
    public ResponseEntity<SessionView> create(@Valid @RequestBody CreateSessionRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        CreateResult result = writer.create(userId(jwt), request.title(), checkKey(idempotencyKey));
        if (result.replayed()) {
            return ResponseEntity.ok(result.session());
        }
        return ResponseEntity.created(URI.create("/sessions/" + result.session().id())).body(result.session());
    }

    /**
     * 모든 세션 목록. 정렬은 createdAt DESC, id DESC로 고정하고 키셋 커서로 넘긴다(전체 건수는 주지 않는다).
     * 날짜는 KST 기준 YYYY-MM-DD이고 양 끝 날짜를 포함한다.
     */
    @GetMapping
    public SessionSummary.Page list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(name = "status", required = false) Set<SessionStatus> statuses,
            @RequestParam(name = "category", required = false) Set<Category> categories,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String participant,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw ApiException.invalid("createdFrom", "createdTo보다 늦을 수 없습니다.");
        }
        String titleQuery = title == null || title.isBlank() ? null : title.strip();
        if (titleQuery != null) {
            int length = titleQuery.codePointCount(0, titleQuery.length());
            if (length < TITLE_SEARCH_MIN_LENGTH || length > TITLE_MAX_LENGTH) {
                throw ApiException.invalid("title", TITLE_SEARCH_MIN_LENGTH + "~" + TITLE_MAX_LENGTH + "자여야 합니다.");
            }
        }
        if (participant != null && !"me".equals(participant)) {
            throw ApiException.invalid("participant", "me만 지정할 수 있습니다.");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.invalid("limit", "1~" + MAX_LIMIT + " 사이여야 합니다.");
        }
        SessionListQuery query = new SessionListQuery(createdFrom, createdTo,
                statuses == null ? Set.of() : statuses, categories == null ? Set.of() : categories,
                titleQuery, participant != null);
        return readPermits.run(() -> queries.list(query, userId(jwt), cursor, limit));
    }

    @GetMapping("/{sessionId}")
    public SessionView detail(@PathVariable UUID sessionId, @AuthenticationPrincipal Jwt jwt) {
        return queries.detail(sessionId, userId(jwt), Role.ADMIN.name().equals(jwt.getClaimAsString("role")));
    }

    @PostMapping("/{sessionId}/join")
    public SessionView join(@PathVariable UUID sessionId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return writer.execute(sessionId, userId(jwt), CommandType.JOIN, checkKey(idempotencyKey));
    }

    @PostMapping("/{sessionId}/leave")
    public SessionView leave(@PathVariable UUID sessionId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return writer.execute(sessionId, userId(jwt), CommandType.LEAVE, checkKey(idempotencyKey));
    }

    @PostMapping("/{sessionId}/end")
    public SessionView end(@PathVariable UUID sessionId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return writer.execute(sessionId, userId(jwt), CommandType.END, checkKey(idempotencyKey));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static String checkKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        if (idempotencyKey.isBlank() || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw ApiException.invalid(IDEMPOTENCY_KEY, "1~" + IDEMPOTENCY_KEY_MAX_LENGTH + "자여야 합니다.");
        }
        return idempotencyKey;
    }

    public record CreateSessionRequest(@NotBlank @Size(max = 100) String title) {

        public CreateSessionRequest {
            title = title == null ? null : title.strip();
        }
    }
}
