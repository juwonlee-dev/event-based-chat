package com.eventchat.session;

import com.eventchat.persistence.session.SessionSearch.Position;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * 목록 키셋 커서. 마지막 행의 (createdAt, id)와 조건 지문을 묶은 불투명 문자열이다.
 */
final class SessionCursors {

    private static final String VERSION = "v1";

    private SessionCursors() {
    }

    static String encode(Position position, String fingerprint) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, position.createdAt());
        String raw = String.join(".", VERSION, Long.toString(micros), position.id().toString(), fingerprint);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Position decode(String cursor, String fingerprint) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\.");
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                throw invalid("형식이 올바르지 않은 커서입니다.");
            }
            if (!parts[3].equals(fingerprint)) {
                throw invalid("커서를 만든 조회 조건과 지금 조건이 다릅니다. 첫 페이지부터 다시 조회해 주세요.");
            }
            return new Position(Instant.EPOCH.plus(Long.parseLong(parts[1]), ChronoUnit.MICROS),
                    UUID.fromString(parts[2]));
        } catch (IllegalArgumentException e) {
            throw invalid("형식이 올바르지 않은 커서입니다.");
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ErrorCode.CURSOR_INVALID, detail);
    }
}
