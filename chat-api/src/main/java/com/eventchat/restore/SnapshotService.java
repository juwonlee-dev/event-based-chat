package com.eventchat.restore;

import com.eventchat.domain.restore.RestoredState;
import com.eventchat.domain.restore.SessionReducer;
import com.eventchat.persistence.session.ChatSession;
import com.eventchat.persistence.session.ChatSessionRepository;
import com.eventchat.persistence.session.SessionSnapshot;
import com.eventchat.persistence.session.SessionSnapshotRepository;
import com.eventchat.session.SessionErrors;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스냅샷 생성·갱신. 관리자 API(동기)와 Kafka 작업자가 같은 경로를 쓴다.
 * 기존 스냅샷이 있고 리듀서 버전이 같으면 그 뒤에 추가된 이벤트만 fold한다. 항상 세션의 현재 마지막 seq까지
 * 갱신하므로 중복 요청은 자연스럽게 합쳐지고, 저장은 더 새로운 seq일 때만 덮어쓴다.
 */
@Service
public class SnapshotService {

    private final ChatSessionRepository sessions;
    private final SessionSnapshotRepository snapshots;
    private final StateFolder folder;
    private final MeterRegistry meters;

    SnapshotService(ChatSessionRepository sessions, SessionSnapshotRepository snapshots, StateFolder folder,
            MeterRegistry meters) {
        this.sessions = sessions;
        this.snapshots = snapshots;
        this.folder = folder;
        this.meters = meters;
    }

    @Transactional
    public SnapshotResult build(UUID sessionId) {
        SnapshotResult result = buildSnapshot(sessionId);
        meters.counter("chat.snapshot.build", "mode", result.mode().name()).increment();
        return result;
    }

    private SnapshotResult buildSnapshot(UUID sessionId) {
        ChatSession session = sessions.findById(sessionId).orElseThrow(SessionErrors::notFound);
        Optional<SessionSnapshot> existing = snapshots.findById(sessionId);
        long previousSeq = existing.map(SessionSnapshot::getLastSeq).orElse(0L);
        boolean usable = existing.filter(s -> s.getReducerVersion() == SessionReducer.VERSION).isPresent();
        SessionReducer reducer = usable
                ? SessionReducer.from(folder.read(existing.get().getState()))
                : SessionReducer.empty();

        long applied = folder.fold(sessionId, reducer, session.getLastSeq(), 0);
        if (usable && applied == 0) {
            return new SnapshotResult(previousSeq, previousSeq, 0, Mode.UNCHANGED, existing.get().getStateHash());
        }
        RestoredState state = reducer.state();
        String hash = folder.hash(state);
        int written = snapshots.upsert(sessionId, state.lastSeq(), state.lastEventTs(), SessionReducer.VERSION,
                folder.json(state), hash);
        if (written == 0) {
            // 다른 작업자가 같거나 더 새로운 스냅샷을 먼저 썼다.
            SessionSnapshot stored = snapshots.findById(sessionId).orElseThrow();
            return new SnapshotResult(stored.getLastSeq(), previousSeq, 0, Mode.UNCHANGED, stored.getStateHash());
        }
        return new SnapshotResult(state.lastSeq(), previousSeq, applied,
                existing.isPresent() ? Mode.UPDATED : Mode.CREATED, hash);
    }

    /**
     * @param previousSeq   갱신 전 스냅샷의 seq(없으면 0)
     * @param appliedEvents 이번에 fold한 이벤트 수(리듀서 버전이 바뀌었으면 처음부터 센다)
     */
    public record SnapshotResult(long lastSeq, long previousSeq, long appliedEvents, Mode mode, String stateHash) {
    }

    public enum Mode {
        CREATED,
        UPDATED,
        UNCHANGED
    }
}
