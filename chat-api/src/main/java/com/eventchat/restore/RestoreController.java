package com.eventchat.restore;

import com.eventchat.domain.user.Role;
import com.eventchat.restore.SnapshotService.SnapshotResult;
import com.eventchat.session.ReadPermits;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions/{sessionId}")
@RequiredArgsConstructor
public class RestoreController {

    private final TimelineService timelines;
    private final SnapshotService snapshots;
    private final ReadPermits readPermits;
    private final RestoreProperties properties;

    /**
     * 지정한 시점의 상태 복원. 현재 참여자와 관리자만 볼 수 있다.
     * <ul>
     *   <li>at(ISO-8601, 오프셋 필수) 또는 atSeq. 둘 다 없으면 현재. 둘 다 주면 400</li>
     *   <li>itemLimit(기본 50, 최대 200)·beforeSeq로 화면 항목을 이전 페이지로 넘긴다</li>
     *   <li>ignoreSnapshot(관리자): 스냅샷 없이 처음부터 fold해 두 경로의 stateHash를 비교한다</li>
     * </ul>
     * 요청마다 권한을 다시 확인해야 하므로 캐시하지 않는다(no-store).
     */
    @GetMapping("/timeline")
    public ResponseEntity<TimelineView> timeline(@PathVariable UUID sessionId,
            @RequestParam(required = false) String at,
            @RequestParam(required = false) Long atSeq,
            @RequestParam(required = false) Integer itemLimit,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(defaultValue = "false") boolean ignoreSnapshot,
            @AuthenticationPrincipal Jwt jwt) {
        boolean admin = isAdmin(jwt);
        if (at != null && atSeq != null) {
            throw ApiException.invalid("at", "at과 atSeq는 함께 쓸 수 없습니다.");
        }
        if (atSeq != null && atSeq < 1) {
            throw ApiException.invalid("atSeq", "1 이상이어야 합니다.");
        }
        if (beforeSeq != null && beforeSeq < 1) {
            throw ApiException.invalid("beforeSeq", "1 이상이어야 합니다.");
        }
        int limit = itemLimit == null ? properties.defaultItemLimit() : itemLimit;
        if (limit < 1 || limit > properties.maxItemLimit()) {
            throw ApiException.invalid("itemLimit", "1~" + properties.maxItemLimit() + " 사이여야 합니다.");
        }
        if (ignoreSnapshot && !admin) {
            throw new ApiException(ErrorCode.ADMIN_ONLY, "ignoreSnapshot은 관리자만 쓸 수 있습니다.");
        }
        Instant atTime = time(at);
        TimelineView view = readPermits.run(() -> timelines.timeline(sessionId, UUID.fromString(jwt.getSubject()),
                admin, atTime, atSeq, beforeSeq, limit, ignoreSnapshot));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }

    /** 스냅샷을 지금의 마지막 seq까지 만들거나 갱신한다(관리자, 동기). DLT로 빠진 요청을 다시 처리할 때도 쓴다. */
    @PostMapping("/snapshots")
    public SnapshotResult snapshot(@PathVariable UUID sessionId, @AuthenticationPrincipal Jwt jwt) {
        if (!isAdmin(jwt)) {
            throw new ApiException(ErrorCode.ADMIN_ONLY, "스냅샷은 관리자만 만들 수 있습니다.");
        }
        return readPermits.run(() -> snapshots.build(sessionId));
    }

    private static boolean isAdmin(Jwt jwt) {
        return Role.ADMIN.name().equals(jwt.getClaimAsString("role"));
    }

    private static Instant time(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.INVALID_TIME_FORMAT,
                    "at은 오프셋을 포함한 ISO-8601 형식(예: 2026-10-21T03:12:45.123Z)이어야 합니다.");
        }
    }
}
