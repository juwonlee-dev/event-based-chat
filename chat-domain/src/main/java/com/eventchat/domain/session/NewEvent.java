package com.eventchat.domain.session;

import java.util.Map;
import java.util.UUID;

/**
 * 판정 결과로 추가할 이벤트. seq와 시각은 저장할 때 정한다.
 *
 * @param keyed 요청의 멱등 키를 이 이벤트에 기록하는지 여부(명령마다 하나)
 */
public record NewEvent(EventType type, UUID subjectId, Map<String, Object> payload, boolean keyed) {
}
