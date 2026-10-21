package com.eventchat.realtime;

import com.eventchat.session.EventView;
import com.eventchat.session.IngestProperties;
import com.eventchat.session.SessionEventsCommitted;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋된 이벤트를 실시간 채널로 내보낸다. 커밋 전에는 내보내지 않으므로 롤백된 이벤트가 화면에 보이는 일이 없다.
 * 커밋된 모든 seq가 나간다(상태 전이, 읽음 커서 포함). 저장하지 않은 typing은 seq가 없고 내보내지도 않는다.
 */
@Component
@RequiredArgsConstructor
class CommittedEventRelay {

    private final RealtimeBus bus;
    private final IngestProperties ingestProperties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void relay(SessionEventsCommitted committed) {
        committed.events().forEach(row ->
                bus.publish(committed.sessionId(), EventView.of(row, ingestProperties.typingTtl())));
    }
}
