package com.eventchat.realtime;

import com.eventchat.session.EventView;
import java.util.UUID;

/**
 * 앱 노드 사이의 세션 이벤트 중계. 게이트웨이와 resume는 이 인터페이스만 호출한다.
 * <p>
 * 지금 구현({@link PatternRealtimeBus})은 모든 노드가 {@code chat:s:*}를 패턴 구독하므로 구독 관리가 필요 없고,
 * 아래 세 메서드는 바로 돌아온다. 노드가 많아져 세션별 sharded 구독으로 바꿀 때 이 경계에 구현을 채운다
 * (docs/design/realtime-scale-out.md).
 */
public interface RealtimeBus {

    /** 커밋된 이벤트를 모든 노드의 그 세션 구독자에게 보낸다. */
    void publish(UUID sessionId, EventView event);

    /** 이 노드에 그 세션의 로컬 구독이 하나 늘었다. */
    void onLocalSubscribe(UUID sessionId);

    /** 이 노드에서 그 세션의 로컬 구독이 하나 줄었다. */
    void onLocalUnsubscribe(UUID sessionId);

    /** 그 세션 채널의 구독이 확정될 때까지 기다린다(resume가 DB를 읽기 전에 부른다). */
    void awaitReady(UUID sessionId);
}
