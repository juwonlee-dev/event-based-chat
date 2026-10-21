package com.eventchat.restore;

import com.eventchat.domain.restore.ReplayEvent;
import com.eventchat.domain.restore.ReplayUnsupportedException;
import com.eventchat.domain.restore.RestoredState;
import com.eventchat.domain.restore.SessionReducer;
import com.eventchat.persistence.session.SessionEventRepository;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스냅샷 생성과 timeline 복원이 함께 쓰는 fold·직렬화.
 * <ul>
 *   <li>이벤트는 5,000건씩 끊어 읽어 리듀서에 넣는다(본문 없이).</li>
 *   <li>상태 해시는 전용 매퍼로 직렬화한 JSON의 SHA-256이다. 레코드 필드 순서가 고정이라 같은 상태면 같은 해시다.
 *       앱 전역 JSON 설정이 바뀌어도 해시가 흔들리지 않도록 전역 매퍼를 쓰지 않는다.</li>
 * </ul>
 */
@Component
class StateFolder {

    private static final int CHUNK = 5_000;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SessionEventRepository events;

    StateFolder(SessionEventRepository events) {
        this.events = events;
    }

    /**
     * reducer의 마지막 seq 다음부터 upToSeq까지 fold한다.
     *
     * @param deadlineNanos {@link System#nanoTime()} 기준 마감. 없으면 0
     * @return fold한 이벤트 수
     */
    long fold(UUID sessionId, SessionReducer reducer, long upToSeq, long deadlineNanos) {
        long applied = 0;
        try {
            while (reducer.lastSeq() < upToSeq) {
                List<ReplayEvent> chunk = events.findForReplay(sessionId, reducer.lastSeq(), upToSeq, CHUNK);
                if (chunk.isEmpty()) {
                    throw new ReplayUnsupportedException(reducer.lastSeq() + 1, "이벤트가 없습니다.");
                }
                chunk.forEach(reducer::apply);
                applied += chunk.size();
                if (deadlineNanos != 0 && System.nanoTime() > deadlineNanos) {
                    throw new ApiException(ErrorCode.RESTORE_TIMEOUT, "복원 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.");
                }
            }
        } catch (ReplayUnsupportedException e) {
            throw new ApiException(ErrorCode.REPLAY_UNSUPPORTED, e.getMessage(), Map.of("seq", e.seq()), null);
        }
        return applied;
    }

    RestoredState read(Map<String, Object> stored) {
        return mapper.convertValue(stored, RestoredState.class);
    }

    String json(RestoredState state) {
        return mapper.writeValueAsString(state);
    }

    String hash(RestoredState state) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json(state).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
