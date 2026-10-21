package com.eventchat.session;

import com.eventchat.domain.session.Decision;
import com.eventchat.domain.session.EventType;
import com.eventchat.domain.session.IngestPolicy;
import com.eventchat.domain.session.NewEvent;
import com.eventchat.domain.session.SessionCommand;
import com.eventchat.domain.session.SessionDecider;
import com.eventchat.domain.session.SessionState;
import com.eventchat.domain.session.Transition;
import com.eventchat.domain.user.Role;
import com.eventchat.persistence.session.ChatSession;
import com.eventchat.persistence.session.ChatSessionRepository;
import com.eventchat.persistence.session.EventOutbox;
import com.eventchat.persistence.session.EventOutboxRepository;
import com.eventchat.persistence.session.SessionEvent;
import com.eventchat.persistence.session.SessionEventRepository;
import com.eventchat.persistence.session.SessionEventRow;
import com.eventchat.persistence.session.SessionParticipant;
import com.eventchat.persistence.session.SessionParticipantRepository;
import com.eventchat.persistence.user.AppUser;
import com.eventchat.persistence.user.AppUserRepository;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import com.eventchat.worker.SnapshotTopics;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 세션 쓰기 경로. 판정 순서는
 * 401 → 403 ADMIN_READ_ONLY → 404 → 멱등 적중 → 403 참여자 확인 → 409 상태 이다.
 * <ol>
 *   <li>노드별 쓰기 허용 수를 기다리지 않고 얻는다(실패하면 503).</li>
 *   <li>세션 행을 잠그고, 멱등 키가 있으면 상태 판정보다 먼저 조회한다.</li>
 *   <li>판정한 이벤트에 seq를 이어 붙여 저장하고 세션·참여자 상태를 갱신한다.</li>
 * </ol>
 * 트랜잭션 안에서는 외부 I/O를 하지 않는다. 저장한 이벤트는 커밋된 뒤 실시간 채널로 나간다
 * ({@link SessionEventsCommitted}). 멱등 재전송이면 처음 이벤트를 다시 내보낸다.
 */
@Service
public class SessionWriter {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(1);
    /** 이벤트가 이만큼 쌓일 때마다 스냅샷 갱신을 요청한다. */
    private static final long SNAPSHOT_EVERY = 100;

    private final TransactionTemplate tx;
    private final Semaphore permits;
    private final IngestPolicy policy;
    private final MeterRegistry meters;
    private final ApplicationEventPublisher eventPublisher;
    private final AppUserRepository users;
    private final ChatSessionRepository sessions;
    private final SessionParticipantRepository participants;
    private final SessionEventRepository events;
    private final EventOutboxRepository outbox;

    public SessionWriter(PlatformTransactionManager transactionManager, WriteProperties properties,
            IngestProperties ingestProperties, MeterRegistry meters, ApplicationEventPublisher eventPublisher,
            AppUserRepository users,
            ChatSessionRepository sessions, SessionParticipantRepository participants,
            SessionEventRepository events, EventOutboxRepository outbox) {
        this.tx = new TransactionTemplate(transactionManager);
        this.permits = new Semaphore(properties.maxConcurrentPerNode());
        this.policy = ingestProperties.toPolicy();
        this.meters = meters;
        this.eventPublisher = eventPublisher;
        this.users = users;
        this.sessions = sessions;
        this.participants = participants;
        this.events = events;
        this.outbox = outbox;
    }

    public CreateResult create(UUID actorId, String title, String idempotencyKey) {
        byte[] requestHash = requestHash("CREATE", title);
        return withPermit(() -> {
            try {
                return tx.execute(status -> createSession(actorId, title, idempotencyKey, requestHash));
            } catch (DataIntegrityViolationException e) {
                if (idempotencyKey == null) {
                    throw e;
                }
                // 같은 키로 동시에 생성했다: 먼저 커밋된 쪽의 결과를 돌려준다.
                return tx.execute(status -> events.findCreatedByIdempotencyKey(actorId, idempotencyKey)
                        .map(created -> replayCreate(created, requestHash))
                        .orElseThrow(() -> e));
            }
        });
    }

    /** 참여·퇴장·종료. 같은 멱등 키로 다시 오면 상태 판정 없이 현재 세션을 돌려준다. */
    public SessionView execute(UUID sessionId, UUID actorId, CommandType type, String idempotencyKey) {
        Outcome outcome = write(sessionId, actorId, idempotencyKey, requestHash(type.name(), ""), null,
                (actor, at) -> command(type, actor));
        return SessionView.of(outcome.session(), outcome.participants());
    }

    /** 메시지. clientEventId가 멱등 키이고, 같은 키로 다시 오면 처음 저장한 이벤트를 돌려준다. */
    public IngestResult message(UUID sessionId, UUID actorId, String clientEventId, String content,
            Instant clientTime) {
        Outcome outcome = write(sessionId, actorId, clientEventId, requestHash(EventType.MESSAGE.name(), content),
                clientTime, (actor, at) -> new SessionCommand.Message(actor.getId(), content));
        return ingested(EventType.MESSAGE, outcome);
    }

    public IngestResult typing(UUID sessionId, UUID actorId, boolean started) {
        Outcome outcome = write(sessionId, actorId, null, null, null,
                (actor, at) -> new SessionCommand.Typing(actor.getId(), started, at));
        return ingested(started ? EventType.TYPING_STARTED : EventType.TYPING_STOPPED, outcome);
    }

    public IngestResult readCursor(UUID sessionId, UUID actorId, long upToSeq) {
        Outcome outcome = write(sessionId, actorId, null, null, null,
                (actor, at) -> new SessionCommand.ReadCursor(actor.getId(), upToSeq, at));
        return ingested(EventType.READ_CURSOR, outcome);
    }

    private Outcome write(UUID sessionId, UUID actorId, String idempotencyKey, byte[] requestHash,
            Instant clientTime, BiFunction<AppUser, Instant, SessionCommand> commandOf) {
        return withPermit(() -> tx.execute(status -> {
            AppUser actor = loadActor(actorId);
            ChatSession session = sessions.findForUpdate(sessionId).orElseThrow(SessionErrors::notFound);
            List<SessionParticipant> current = new ArrayList<>(participants.findBySessionIdOrderByJoinSeq(sessionId));
            if (idempotencyKey != null) {
                Optional<SessionEvent> previous =
                        events.findBySessionIdAndActorIdAndClientEventId(sessionId, actorId, idempotencyKey);
                if (previous.isPresent()) {
                    checkSameRequest(previous.get(), requestHash);
                    committed(sessionId, List.of(previous.get()));
                    return new Outcome(session, current, Result.REPLAYED, List.of(previous.get()));
                }
            }

            Instant at = latest(sessions.clockTimestamp(), session.getLastEventAt());
            return switch (SessionDecider.decide(state(session, current), commandOf.apply(actor, at), policy)) {
                case Decision.Rejected rejected -> throw SessionErrors.rejected(rejected.reason(), session.getStatus());
                case Decision.Suppressed suppressed -> new Outcome(session, current, Result.SUPPRESSED, List.of());
                case Decision.Accepted accepted -> {
                    long seqBefore = session.getLastSeq();
                    List<SessionEvent> appended = append(session, actor, accepted.events(), idempotencyKey,
                            requestHash, at, clientTime, current);
                    if (accepted.transition() != null) {
                        applyTransition(session, accepted.transition(), at, current);
                    }
                    requestSnapshotIfNeeded(session, seqBefore, accepted.transition());
                    committed(sessionId, appended);
                    yield new Outcome(session, current, Result.APPLIED, appended);
                }
            };
        }));
    }

    private CreateResult createSession(UUID actorId, String title, String idempotencyKey, byte[] requestHash) {
        AppUser actor = loadActor(actorId);
        if (idempotencyKey != null) {
            Optional<SessionEvent> created = events.findCreatedByIdempotencyKey(actorId, idempotencyKey);
            if (created.isPresent()) {
                return replayCreate(created.get(), requestHash);
            }
        }
        Instant now = sessions.clockTimestamp();
        ChatSession session = sessions.save(ChatSession.create(title, actor.getCategory(), actorId, now));
        List<SessionParticipant> joined = new ArrayList<>();
        committed(session.getId(), append(session, actor, SessionDecider.create(actorId, actor.getRole(),
                actor.getCategory(), actor.getDisplayName(), title), idempotencyKey, requestHash, now, null, joined));
        return new CreateResult(SessionView.of(session, joined), false);
    }

    private CreateResult replayCreate(SessionEvent created, byte[] requestHash) {
        checkSameRequest(created, requestHash);
        ChatSession session = sessions.findById(created.getSessionId()).orElseThrow(SessionErrors::notFound);
        return new CreateResult(SessionView.of(session,
                participants.findBySessionIdOrderByJoinSeq(session.getId())), true);
    }

    /** 이벤트마다 seq를 이어 붙여 저장하고, 참여자 행의 현재값(참여·typing·읽음)에도 반영한다. */
    private List<SessionEvent> append(ChatSession session, AppUser actor, List<NewEvent> newEvents,
            String idempotencyKey, byte[] requestHash, Instant at, Instant clientTime,
            List<SessionParticipant> sessionParticipants) {
        List<SessionEvent> appended = new ArrayList<>();
        for (NewEvent event : newEvents) {
            long seq = session.nextSeq(at);
            boolean keyed = event.keyed() && idempotencyKey != null;
            Map<String, Object> payload = event.payload();
            String content = null;
            Integer msgNo = null;
            switch (event.type()) {
                case JOIN -> {
                    sessionParticipants.add(participants.save(SessionParticipant.join(session.getId(),
                            actor.getId(), actor.getRole(), actor.getCategory(), actor.getDisplayName(), seq, at)));
                    session.participantJoined();
                }
                case LEAVE -> {
                    find(sessionParticipants, actor.getId()).leave(seq, at);
                    session.participantLeft();
                }
                case MESSAGE -> {
                    // 본문은 content 컬럼에만 둔다(상태 fold 쿼리가 읽지 않도록).
                    content = (String) payload.get("content");
                    payload = null;
                    msgNo = session.nextMsgNo();
                    find(sessionParticipants, actor.getId()).messageSent(seq, msgNo);
                }
                case TYPING_STARTED -> find(sessionParticipants, actor.getId()).startTyping(seq, at);
                case TYPING_STOPPED -> find(sessionParticipants, actor.getId()).stopTyping();
                case READ_CURSOR -> {
                    long upToSeq = ((Number) payload.get("upToSeq")).longValue();
                    int readMsgNo = events.findLastMsgNoUpTo(session.getId(), upToSeq);
                    payload = Map.of("upToSeq", upToSeq, "readMsgNo", readMsgNo);
                    find(sessionParticipants, actor.getId()).read(upToSeq, readMsgNo, at);
                }
                default -> {
                }
            }
            appended.add(events.save(SessionEvent.of(session.getId(), seq, event.type(), actor.getId(),
                    event.subjectId(), keyed ? idempotencyKey : null, keyed ? requestHash : null, payload,
                    content, msgNo, at, clientTime)));
        }
        return appended;
    }

    private static void applyTransition(ChatSession session, Transition transition, Instant at,
            List<SessionParticipant> sessionParticipants) {
        session.apply(transition, at);
        if (transition.to().isTerminal()) {
            sessionParticipants.forEach(SessionParticipant::deactivate);
        }
    }

    /** 종단 전이이거나 seq가 100 단위 경계를 넘으면 같은 트랜잭션에서 스냅샷 갱신을 요청한다. */
    private void requestSnapshotIfNeeded(ChatSession session, long seqBefore, Transition transition) {
        boolean crossed = seqBefore / SNAPSHOT_EVERY < session.getLastSeq() / SNAPSHOT_EVERY;
        if (crossed || (transition != null && transition.to().isTerminal())) {
            String sessionId = session.getId().toString();
            outbox.save(EventOutbox.of(SnapshotTopics.REQUESTS, sessionId,
                    Map.of("sessionId", sessionId, "upToSeq", session.getLastSeq())));
        }
    }

    private void committed(UUID sessionId, List<SessionEvent> sessionEvents) {
        eventPublisher.publishEvent(new SessionEventsCommitted(sessionId,
                sessionEvents.stream().map(SessionEventRow::of).toList()));
    }

    /** 저장하지 않고 걸러 낸 요청(재전송 중복, 같은 상태의 반복)은 지표로만 남긴다. */
    private IngestResult ingested(EventType type, Outcome outcome) {
        return switch (outcome.result()) {
            case APPLIED -> new IngestResult(IngestResult.Status.STORED, outcome.events().getFirst());
            case REPLAYED -> {
                countSuppressed(type, "DUPLICATE");
                yield new IngestResult(IngestResult.Status.DUPLICATE, outcome.events().getFirst());
            }
            case SUPPRESSED -> {
                countSuppressed(type, "REPEATED");
                yield new IngestResult(IngestResult.Status.SUPPRESSED, null);
            }
        };
    }

    private void countSuppressed(EventType type, String reason) {
        meters.counter("chat.events.suppressed", "type", type.name(), "reason", reason).increment();
    }

    private static SessionState state(ChatSession session, List<SessionParticipant> sessionParticipants) {
        return new SessionState(session.getStatus(), session.getLastSeq(), sessionParticipants.stream()
                .map(p -> new SessionState.Participant(p.getUserId(), p.isLeft(), p.getTypingAt(), p.getReadSeq(),
                        p.getLastReadStoreAt()))
                .toList());
    }

    private AppUser loadActor(UUID actorId) {
        AppUser actor = users.findById(actorId)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_INVALID, "사용자를 찾을 수 없습니다."));
        if (actor.getRole() == Role.ADMIN) {
            throw SessionErrors.adminReadOnly();
        }
        return actor;
    }

    private static SessionCommand command(CommandType type, AppUser actor) {
        return switch (type) {
            case JOIN -> new SessionCommand.Join(actor.getId(), actor.getRole(), actor.getCategory(),
                    actor.getDisplayName());
            case LEAVE -> new SessionCommand.Leave(actor.getId());
            case END -> new SessionCommand.End(actor.getId());
        };
    }

    /** 같은 키인데 다른 요청이면 처음 결과를 돌려주지 않는다. */
    private static void checkSameRequest(SessionEvent previous, byte[] requestHash) {
        if (!Arrays.equals(previous.getRequestHash(), requestHash)) {
            throw SessionErrors.idempotencyKeyReused();
        }
    }

    private static SessionParticipant find(List<SessionParticipant> sessionParticipants, UUID userId) {
        return sessionParticipants.stream()
                .filter(p -> p.getUserId().equals(userId))
                .findFirst()
                .orElseThrow();
    }

    /** 잠금을 얻은 뒤의 DB 시각을 쓰되, 세션 안에서는 시각이 뒤로 가지 않게 한다. */
    private static Instant latest(Instant dbNow, Instant lastEventAt) {
        return dbNow.isAfter(lastEventAt) ? dbNow : lastEventAt;
    }

    private <T> T withPermit(Supplier<T> work) {
        if (!permits.tryAcquire()) {
            throw ApiException.unavailable(RETRY_AFTER);
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    private static byte[] requestHash(String command, String body) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((command + "\n" + body).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public enum CommandType {
        JOIN,
        LEAVE,
        END
    }

    public record CreateResult(SessionView session, boolean replayed) {
    }

    /** @param event 저장했거나(STORED) 처음 저장된(DUPLICATE) 이벤트. SUPPRESSED면 null */
    public record IngestResult(Status status, SessionEvent event) {

        public enum Status {
            STORED,
            DUPLICATE,
            SUPPRESSED
        }
    }

    private enum Result {
        APPLIED,
        REPLAYED,
        SUPPRESSED
    }

    private record Outcome(ChatSession session, List<SessionParticipant> participants, Result result,
            List<SessionEvent> events) {
    }
}
