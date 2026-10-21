package com.eventchat.domain.session;

import java.time.Duration;

/**
 * typing·읽음 커서 저장 억제 기준.
 *
 * @param typingTtl               STARTED 뒤 이 시간이 지나면 입력을 멈춘 것으로 본다
 * @param typingMinStoreInterval  입력 중에 다시 온 STARTED는 이 간격이 지나야 저장한다
 * @param readMinStoreInterval    읽음 커서는 이 간격에 한 번만 저장한다
 */
public record IngestPolicy(Duration typingTtl, Duration typingMinStoreInterval, Duration readMinStoreInterval) {
}
