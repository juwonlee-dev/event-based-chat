package com.eventchat.session;

import com.eventchat.domain.session.Decision;
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
import com.eventchat.persistence.session.SessionParticipant;
import com.eventchat.persistence.session.SessionParticipantRepository;
import com.eventchat.persistence.user.AppUser;
import com.eventchat.persistence.user.AppUserRepository;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import com.eventchat.worker.SnapshotTopics;
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
import java.util.function.Supplier;
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
 * 트랜잭션 안에서는 외부 I/O를 하지 않는다.
 */
@Service
public class SessionWriter {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(1);
    /** 이벤트가 이만큼 쌓일 때마다 스냅샷 갱신을 요청한다. */
    private static final long SNAPSHOT_EVERY = 100;

    private final TransactionTemplate tx;
    private final Semaphore permits;
    private final AppUserRepository users;
    private final ChatSessionRepository sessions;
    private final SessionParticipantRepository participants;
    private final SessionEventRepository events;
    private final EventOutboxRepository outbox;

    public SessionWriter(PlatformTransactionManager transactionManager, WriteProperties properties,
            AppUserRepository users, ChatSessionRepository sessions, SessionParticipantRepository participants,
            SessionEventRepository events, EventOutboxRepository outbox) {
        this.tx = new TransactionTemplate(transactionManager);
        this.permits = new Semaphore(properties.maxConcurrentPerNode());
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

    public SessionView execute(UUID sessionId, UUID actorId, CommandType type, String idempotencyKey) {
        byte[] requestHash = requestHash(type.name(), "");
        return withPermit(() -> tx.execute(status -> {
            AppUser actor = loadActor(actorId);
            ChatSession session = sessions.findForUpdate(sessionId).orElseThrow(SessionErrors::notFound);
            if (idempotencyKey != null) {
                Optional<SessionEvent> previous =
                        events.findBySessionIdAndActorIdAndClientEventId(sessionId, actorId, idempotencyKey);
                if (previous.isPresent()) {
                    checkSameRequest(previous.get(), requestHash);
                    return SessionView.of(session, participants.findBySessionIdOrderByJoinSeq(sessionId));
                }
            }

            List<SessionParticipant> current = new ArrayList<>(participants.findBySessionIdOrderByJoinSeq(sessionId));
            SessionState state = new SessionState(session.getStatus(), current.stream()
                    .map(p -> new SessionState.Participant(p.getUserId(), p.isLeft()))
                    .toList());
            return switch (SessionDecider.decide(state, command(type, actor))) {
                case Decision.Rejected rejected -> throw SessionErrors.rejected(rejected.reason(), session.getStatus());
                case Decision.Accepted accepted -> {
                    long seqBefore = session.getLastSeq();
                    Instant at = latest(sessions.clockTimestamp(), session.getLastEventAt());
                    append(session, actor, accepted.events(), idempotencyKey, requestHash, at, current);
                    applyTransition(session, accepted.transition(), at, current);
                    requestSnapshotIfNeeded(session, seqBefore, accepted.transition());
                    yield SessionView.of(session, current);
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
        append(session, actor, SessionDecider.create(actorId, actor.getRole(), actor.getCategory(),
                actor.getDisplayName(), title), idempotencyKey, requestHash, now, joined);
        return new CreateResult(SessionView.of(session, joined), false);
    }

    private CreateResult replayCreate(SessionEvent created, byte[] requestHash) {
        checkSameRequest(created, requestHash);
        ChatSession session = sessions.findById(created.getSessionId()).orElseThrow(SessionErrors::notFound);
        return new CreateResult(SessionView.of(session,
                participants.findBySessionIdOrderByJoinSeq(session.getId())), true);
    }

    /** 이벤트마다 seq를 이어 붙여 저장하고, 참여·퇴장은 참여자 행에도 반영한다. */
    private void append(ChatSession session, AppUser actor, List<NewEvent> newEvents, String idempotencyKey,
            byte[] requestHash, Instant at, List<SessionParticipant> sessionParticipants) {
        for (NewEvent event : newEvents) {
            long seq = session.nextSeq(at);
            boolean keyed = event.keyed() && idempotencyKey != null;
            events.save(SessionEvent.of(session.getId(), seq, event.type(), actor.getId(), event.subjectId(),
                    keyed ? idempotencyKey : null, keyed ? requestHash : null, event.payload(), at));
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
                default -> {
                }
            }
        }
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
        if (crossed || transition.to().isTerminal()) {
            String sessionId = session.getId().toString();
            outbox.save(EventOutbox.of(SnapshotTopics.REQUESTS, sessionId,
                    Map.of("sessionId", sessionId, "upToSeq", session.getLastSeq())));
        }
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
}
