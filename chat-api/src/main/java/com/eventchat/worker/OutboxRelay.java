package com.eventchat.worker;

import com.eventchat.persistence.session.EventOutbox;
import com.eventchat.persistence.session.EventOutboxRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * outbox → Kafka. 모든 노드가 돌린다.
 * <p>
 * 짧은 트랜잭션에서 {@code FOR UPDATE SKIP LOCKED}로 최대 100행을 잡고, 보내고(flush), 성공한 행만 지운 뒤 커밋한다.
 * 다른 노드가 잡은 행은 건너뛰므로 동시에 같은 행을 보내지 않는다. 그래도 커밋 전에 죽으면 다시 보내므로
 * at-least-once이고, 소비 쪽이 단조 UPSERT라서 중복·순서는 문제가 되지 않는다.
 * outbox를 쓴 트랜잭션이 커밋되면 바로 깨우고, 놓친 것은 1초 폴링이 줍는다.
 * Kafka가 없으면 행이 쌓이기만 하고 쓰기와 실시간에는 영향이 없다.
 */
@Slf4j
@Component
public class OutboxRelay {

    private static final int BATCH = 100;
    private static final long SEND_TIMEOUT_MS = 5_000;

    private final TransactionTemplate tx;
    private final EventOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper jsonMapper;
    private final AtomicBoolean woken = new AtomicBoolean();

    public OutboxRelay(PlatformTransactionManager transactionManager, EventOutboxRepository outbox,
            KafkaTemplate<String, String> kafka, JsonMapper jsonMapper) {
        this.tx = new TransactionTemplate(transactionManager);
        this.outbox = outbox;
        this.kafka = kafka;
        this.jsonMapper = jsonMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAppended(OutboxAppended appended) {
        if (woken.compareAndSet(false, true)) {
            Thread.ofVirtual().start(() -> {
                woken.set(false);
                relay();
            });
        }
    }

    @Scheduled(fixedDelayString = "${chat.outbox.poll-interval}")
    void poll() {
        relay();
    }

    private void relay() {
        try {
            while (relayBatch() == BATCH) {
                // 한 번에 다 못 보냈으면 이어서 보낸다.
            }
        } catch (RuntimeException e) {
            log.warn("outbox relay failed, will retry: {}", e.toString());
        }
    }

    /** @return 보내고 지운 행 수. 하나라도 실패하면 거기서 멈추고(다음 주기에 다시) 성공한 것만 지운다 */
    private int relayBatch() {
        Integer sent = tx.execute(status -> {
            List<EventOutbox> rows = outbox.claim(BATCH);
            if (rows.isEmpty()) {
                return 0;
            }
            List<CompletableFuture<SendResult<String, String>>> sends = rows.stream()
                    .map(row -> kafka.send(row.getTopic(), row.getKey(), jsonMapper.writeValueAsString(row.getPayload())))
                    .toList();
            kafka.flush();
            List<Long> done = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                if (!await(sends.get(i))) {
                    break;
                }
                done.add(rows.get(i).getId());
            }
            outbox.deleteAllByIdInBatch(done);
            return done.size() == rows.size() ? done.size() : -1;
        });
        return sent == null ? 0 : sent;
    }

    private static boolean await(CompletableFuture<SendResult<String, String>> send) {
        try {
            send.get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("outbox send failed: {}", e.getCause() == null ? e.toString() : e.getCause().toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
