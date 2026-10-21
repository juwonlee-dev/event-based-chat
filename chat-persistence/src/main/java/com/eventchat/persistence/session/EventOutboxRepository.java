package com.eventchat.persistence.session;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventOutboxRepository extends JpaRepository<EventOutbox, Long> {

    /**
     * 발행할 행을 잠그고 가져온다. 다른 노드가 잠근 행은 건너뛰므로 모든 노드가 relay를 돌려도 같은 행을
     * 동시에 보내지 않는다(PostgreSQL SKIP LOCKED).
     */
    @Query(value = "SELECT * FROM event_outbox ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<EventOutbox> claim(int limit);

    @Query("select new com.eventchat.persistence.session.OutboxBacklog(count(o), min(o.createdAt)) from EventOutbox o")
    OutboxBacklog backlog();
}
