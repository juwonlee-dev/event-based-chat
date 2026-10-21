package com.eventchat.stomp;

import com.eventchat.domain.session.Rejection;
import com.eventchat.persistence.session.ChatSession;
import com.eventchat.persistence.session.ChatSessionRepository;
import com.eventchat.persistence.session.SessionEventRepository;
import com.eventchat.persistence.session.SessionEventRow;
import com.eventchat.persistence.session.SessionEventSearchRepository;
import com.eventchat.persistence.session.SessionParticipant;
import com.eventchat.persistence.session.SessionParticipantRepository;
import com.eventchat.session.EventView;
import com.eventchat.session.IngestProperties;
import com.eventchat.web.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션 topic 구독의 인가와 resume.
 * resume는 "브로커 구독 등록 → DB 조회 → 라이브" 순서라서 그 사이에 빠지는 이벤트가 없고, 겹치는 부분은 클라이언트가 seq로 거른다.
 */
@Service
public class SessionSubscriptions {

    private final ChatSessionRepository sessions;
    private final SessionParticipantRepository participants;
    private final SessionEventRepository events;
    private final StompDelivery delivery;
    private final StompProperties properties;
    private final IngestProperties ingestProperties;
    private final Semaphore resumePermits;
    private final MeterRegistry meters;

    public SessionSubscriptions(ChatSessionRepository sessions, SessionParticipantRepository participants,
            SessionEventRepository events, StompDelivery delivery, StompProperties properties,
            IngestProperties ingestProperties, MeterRegistry meters) {
        this.sessions = sessions;
        this.participants = participants;
        this.events = events;
        this.delivery = delivery;
        this.properties = properties;
        this.ingestProperties = ingestProperties;
        this.resumePermits = new Semaphore(properties.resumeMaxConcurrent());
        this.meters = meters;
    }

    /** 현재 참여자(퇴장하지 않음)이고 세션이 WAITING·IN_PROGRESS일 때만 구독할 수 있다. */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> rejection(UUID sessionId, UUID userId) {
        Optional<ChatSession> found = sessions.findById(sessionId);
        if (found.isEmpty()) {
            return Optional.of(rejected(sessionId, ErrorCode.SESSION_NOT_FOUND.name(), null, null));
        }
        ChatSession session = found.get();
        Optional<SessionParticipant> self = participants.findById(new SessionParticipant.Key(sessionId, userId));
        Rejection reason = null;
        if (self.isEmpty()) {
            reason = Rejection.NOT_PARTICIPANT;
        } else if (self.get().isLeft()) {
            reason = Rejection.PARTICIPANT_LEFT;
        } else if (session.getStatus().isTerminal()) {
            reason = Rejection.SESSION_NOT_ACTIVE;
        }
        return Optional.ofNullable(reason)
                .map(r -> rejected(sessionId, r.name(), session.getStatus().name(), session.getLastSeq()));
    }

    /**
     * afterSeq 다음 이벤트를 그 구독에만 보내고 끝에 RESUMED를 붙인다.
     * 건수·바이트 상한을 넘거나 동시 처리 수가 차면 아무것도 보내지 않고 SYSTEM_RESYNC로 보충 API를 쓰게 한다.
     */
    public void resume(String simpSessionId, String subscriptionId, UUID sessionId, long afterSeq) {
        if (!resumePermits.tryAcquire()) {
            resync(simpSessionId, subscriptionId, sessionId, afterSeq);
            return;
        }
        try {
            List<SessionEventRow> rows = events.findRange(
                    new SessionEventSearchRepository.Range(sessionId, afterSeq, null, null, null),
                    properties.resumeMaxEvents() + 1);
            if (rows.size() > properties.resumeMaxEvents()) {
                resync(simpSessionId, subscriptionId, sessionId, afterSeq);
                return;
            }
            List<byte[]> frames = new ArrayList<>(rows.size());
            long bytes = 0;
            for (SessionEventRow row : rows) {
                byte[] json = delivery.json(EventView.of(row, ingestProperties.typingTtl()));
                bytes += json.length;
                if (bytes > properties.resumeMaxBytes().toBytes()) {
                    resync(simpSessionId, subscriptionId, sessionId, afterSeq);
                    return;
                }
                frames.add(json);
            }
            frames.forEach(json -> delivery.toSubscription(simpSessionId, subscriptionId, sessionId, json));

            Map<String, Object> resumed = new LinkedHashMap<>();
            resumed.put("kind", "RESUMED");
            resumed.put("sessionId", sessionId);
            resumed.put("fromSeq", afterSeq + 1);
            resumed.put("toSeq", rows.isEmpty() ? afterSeq : rows.getLast().seq());
            delivery.toSubscription(simpSessionId, subscriptionId, sessionId, delivery.json(resumed));
            meters.counter("chat.realtime.resume", "result", "resumed").increment();
        } finally {
            resumePermits.release();
        }
    }

    private void resync(String simpSessionId, String subscriptionId, UUID sessionId, long afterSeq) {
        Map<String, Object> resync = new LinkedHashMap<>();
        resync.put("kind", "SYSTEM_RESYNC");
        resync.put("sessionId", sessionId);
        resync.put("fromSeq", afterSeq + 1);
        delivery.toSubscription(simpSessionId, subscriptionId, sessionId, delivery.json(resync));
        meters.counter("chat.realtime.resume", "result", "resync").increment();
    }

    private static Map<String, Object> rejected(UUID sessionId, String code, String sessionStatus, Long lastSeq) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "SUBSCRIBE_REJECTED");
        body.put("sessionId", sessionId);
        body.put("code", code);
        body.put("sessionStatus", sessionStatus);
        body.put("lastSeq", lastSeq);
        return body;
    }
}
