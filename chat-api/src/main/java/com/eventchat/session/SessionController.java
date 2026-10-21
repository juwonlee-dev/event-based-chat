package com.eventchat.session;

import com.eventchat.domain.user.Role;
import com.eventchat.session.SessionWriter.CommandType;
import com.eventchat.session.SessionWriter.CreateResult;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;

    private final SessionWriter writer;
    private final SessionQueryService queries;

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
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 값이 올바르지 않습니다.",
                    Map.of("errors", List.of(Map.of("field", IDEMPOTENCY_KEY,
                            "reason", "1~" + IDEMPOTENCY_KEY_MAX_LENGTH + "자여야 합니다."))), null);
        }
        return idempotencyKey;
    }

    public record CreateSessionRequest(@NotBlank @Size(max = 100) String title) {

        public CreateSessionRequest {
            title = title == null ? null : title.strip();
        }
    }
}
