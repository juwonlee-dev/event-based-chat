package com.eventchat.session;

import com.eventchat.domain.session.IngestPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * typing·읽음 커서 저장 억제 기준.
 */
@ConfigurationProperties("chat.ingest")
public record IngestProperties(Duration typingTtl, Duration typingMinStoreInterval, Duration readMinStoreInterval) {

    IngestPolicy toPolicy() {
        return new IngestPolicy(typingTtl, typingMinStoreInterval, readMinStoreInterval);
    }
}
