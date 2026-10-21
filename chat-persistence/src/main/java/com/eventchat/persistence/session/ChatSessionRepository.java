package com.eventchat.persistence.session;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    /** 쓰기 직렬화용 행 잠금. 대기 상한은 앱 계정의 lock_timeout(1s)을 따른다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChatSession s where s.id = :id")
    Optional<ChatSession> findForUpdate(UUID id);

    /**
     * 이벤트 시각은 노드마다 다른 앱 시계 대신 DB 시계로 정한다.
     * now()는 트랜잭션 시작 시각이라 잠금을 기다린 시간이 빠지므로 clock_timestamp()를 쓴다.
     */
    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant clockTimestamp();
}
