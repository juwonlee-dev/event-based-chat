package com.eventchat.presence;

import com.eventchat.domain.session.SessionCommand;
import com.eventchat.domain.session.SessionStatus;
import com.eventchat.persistence.session.SessionParticipantRepository;
import com.eventchat.session.SessionWriter;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Redis가 판정한 전이를 DB 이벤트로 남긴다. 연결·해제를 처리하는 스레드를 막지 않도록 가상 스레드에서 실행한다.
 * 순서가 뒤바뀌어도 ver가 작은 신호는 쓰기 경로에서 버려진다. 실패하면 대조 작업이 다시 맞춘다.
 */
@Slf4j
@Component
class PresenceRecorder {

    private final SessionWriter writer;
    private final SessionParticipantRepository participants;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    PresenceRecorder(SessionWriter writer, SessionParticipantRepository participants) {
        this.writer = writer;
        this.participants = participants;
    }

    /** 그 사용자가 참여 중인 진행 중 세션마다 기록한다. */
    void record(SessionCommand.Presence command) {
        executor.execute(() -> participants.findSessionIdsOfActiveUser(command.userId(), SessionStatus.IN_PROGRESS)
                .forEach(sessionId -> write(sessionId, command)));
    }

    void record(UUID sessionId, SessionCommand.Presence command) {
        executor.execute(() -> write(sessionId, command));
    }

    private void write(UUID sessionId, SessionCommand.Presence command) {
        try {
            writer.presence(sessionId, command);
        } catch (RuntimeException e) {
            log.warn("presence write failed, reconcile will retry: session={} user={} status={}",
                    sessionId, command.userId(), command.status(), e);
        }
    }

    @PreDestroy
    void close() {
        executor.close();
    }
}
