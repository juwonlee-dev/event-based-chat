package com.eventchat.persistence.session;

import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 세션 참여자. 다시 참여할 수 없으므로 세션·사용자당 1행이다.
 * 역할·분류코드·표시 이름은 참여 시점 값을 복사해 둔다.
 */
@Entity
@Table(name = "session_participant")
@IdClass(SessionParticipant.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionParticipant extends AssignedIdEntity<SessionParticipant.Key> {

    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Category category;

    @Column(name = "display_name", nullable = false, updatable = false)
    private String displayName;

    /** 진행 중인 세션에 참여하고 있는지. 퇴장하거나 세션이 종단 상태가 되면 false. */
    @Column(nullable = false)
    private boolean active;

    @Column(name = "join_seq", nullable = false, updatable = false)
    private long joinSeq;

    @Column(name = "left_seq")
    private Long leftSeq;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    public static SessionParticipant join(UUID sessionId, UUID userId, Role role, Category category,
            String displayName, long seq, Instant at) {
        SessionParticipant participant = new SessionParticipant();
        participant.sessionId = sessionId;
        participant.userId = userId;
        participant.role = role;
        participant.category = category;
        participant.displayName = displayName;
        participant.active = true;
        participant.joinSeq = seq;
        participant.joinedAt = at;
        return participant;
    }

    public boolean isLeft() {
        return leftSeq != null;
    }

    public void leave(long seq, Instant at) {
        this.leftSeq = seq;
        this.leftAt = at;
        this.active = false;
    }

    public void deactivate() {
        this.active = false;
    }

    @Override
    public Key getId() {
        return new Key(sessionId, userId);
    }

    public record Key(UUID sessionId, UUID userId) implements Serializable {
    }
}
