package com.eventchat.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventchat.persistence.session.SessionSearch.Position;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionCursorsTest {

    private final Position position = new Position(Instant.parse("2026-10-22T03:12:45.123456Z"), UUID.randomUUID());

    @Test
    @DisplayName("마이크로초까지 그대로 돌아온다(키셋 경계가 어긋나지 않도록)")
    void roundTrip() {
        String cursor = SessionCursors.encode(position, "fp");
        assertThat(SessionCursors.decode(cursor, "fp")).isEqualTo(position);
    }

    @Test
    @DisplayName("조회 조건이 바뀌면 같은 커서를 쓸 수 없다")
    void fingerprintMismatch() {
        String cursor = SessionCursors.encode(position, "status=WAITING");
        assertThatThrownBy(() -> SessionCursors.decode(cursor, "status=COMPLETED"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CURSOR_INVALID));
    }

    @Test
    @DisplayName("손으로 만든 커서는 CURSOR_INVALID")
    void tampered() {
        assertThatThrownBy(() -> SessionCursors.decode("not-base64!!", "fp"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CURSOR_INVALID));
        assertThatThrownBy(() -> SessionCursors.decode("djEuYWJjLnh5ei5mcA", "fp"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CURSOR_INVALID));
    }
}
