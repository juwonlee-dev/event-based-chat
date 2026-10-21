package com.eventchat.persistence.session;

import com.eventchat.domain.session.EventType;
import com.eventchat.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 세션 이벤트. 추가 전용이라 저장한 뒤에는 바꾸지 않는다(앱 계정에도 UPDATE 권한이 없다).
 */
@Entity
@Immutable
@Table(name = "session_event")
@IdClass(SessionEvent.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionEvent extends AssignedIdEntity<SessionEvent.Key> {

    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Id
    private long seq;

    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "client_event_id")
    private String clientEventId;

    @Column(name = "request_hash")
    private byte[] requestHash;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;

    private String content;

    @Column(name = "msg_no")
    private Integer msgNo;

    @Column(name = "server_ts", nullable = false)
    private Instant serverTs;

    @Column(name = "client_ts")
    private Instant clientTs;

    @Column(name = "schema_version", nullable = false)
    private short schemaVersion;

    public static SessionEvent of(UUID sessionId, long seq, EventType type, UUID actorId, UUID subjectId,
            String clientEventId, byte[] requestHash, Map<String, Object> payload, Instant serverTs) {
        SessionEvent event = new SessionEvent();
        event.sessionId = sessionId;
        event.seq = seq;
        event.type = type;
        event.actorId = actorId;
        event.subjectId = subjectId;
        event.clientEventId = clientEventId;
        event.requestHash = requestHash;
        event.payload = payload;
        event.serverTs = serverTs;
        event.schemaVersion = 1;
        return event;
    }

    @Override
    public Key getId() {
        return new Key(sessionId, seq);
    }

    public record Key(UUID sessionId, long seq) implements Serializable {
    }
}
