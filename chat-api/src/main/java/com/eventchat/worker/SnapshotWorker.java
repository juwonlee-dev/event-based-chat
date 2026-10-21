package com.eventchat.worker;

import com.eventchat.restore.SnapshotService;
import com.eventchat.restore.SnapshotService.SnapshotResult;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스냅샷 갱신 요청 소비(group chat-snapshotter). 요청의 upToSeq와 관계없이 항상 DB의 마지막 seq까지 갱신하므로
 * 밀린 요청 여러 개는 한 번의 갱신으로 합쳐진다. 실패 처리는 {@link KafkaWorkerConfig}.
 */
@Slf4j
@Component
class SnapshotWorker {

    private final SnapshotService snapshots;
    private final JsonMapper jsonMapper;

    SnapshotWorker(SnapshotService snapshots, JsonMapper jsonMapper) {
        this.snapshots = snapshots;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = SnapshotTopics.REQUESTS, groupId = "chat-snapshotter")
    void onRequest(String payload) {
        Request request = jsonMapper.readValue(payload, Request.class);
        if (request.sessionId() == null) {
            throw new IllegalArgumentException("sessionId가 없습니다: " + payload);
        }
        SnapshotResult result = snapshots.build(request.sessionId());
        log.debug("snapshot {}: session={} seq={} applied={}", result.mode(), request.sessionId(), result.lastSeq(),
                result.appliedEvents());
    }

    record Request(UUID sessionId, long upToSeq) {
    }
}
