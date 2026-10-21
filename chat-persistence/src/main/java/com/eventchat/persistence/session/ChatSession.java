package com.eventchat.persistence.session;

import com.eventchat.domain.session.SessionStatus;
import com.eventchat.domain.session.Transition;
import com.eventchat.domain.session.TransitionReason;
import com.eventchat.domain.user.Category;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * 상담 세션. 상태 머신 행이면서 세션별 seq 카운터다.
 * 쓰기는 항상 이 행을 잠근 상태에서 seq를 이어 붙인다.
 */
@Entity
@Table(name = "chat_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatSession {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "participant_count", nullable = false)
    private short participantCount;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason")
    private TransitionReason endReason;

    public static ChatSession create(String title, Category category, UUID createdBy, Instant now) {
        ChatSession session = new ChatSession();
        session.title = title;
        session.category = category;
        session.status = SessionStatus.WAITING;
        session.createdBy = createdBy;
        session.lastEventAt = now;
        session.createdAt = now;
        return session;
    }

    /** 다음 seq를 부여하고 마지막 이벤트 시각을 옮긴다. */
    public long nextSeq(Instant at) {
        this.lastEventAt = at;
        return ++lastSeq;
    }

    /** 세션 안 메시지 번호(1부터). 안 읽은 수 = message_count − read_msg_no. */
    public int nextMsgNo() {
        return ++messageCount;
    }

    public void participantJoined() {
        participantCount++;
    }

    public void participantLeft() {
        participantCount--;
    }

    public void apply(Transition transition, Instant at) {
        this.status = transition.to();
        if (transition.to() == SessionStatus.IN_PROGRESS) {
            this.startedAt = at;
        }
        if (transition.to().isTerminal()) {
            this.endedAt = at;
            this.endReason = transition.reason();
        }
    }
}
