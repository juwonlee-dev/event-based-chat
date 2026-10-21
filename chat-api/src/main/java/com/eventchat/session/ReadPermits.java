package com.eventchat.session;

import com.eventchat.web.error.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 무거운 읽기의 노드별 동시 실행 상한. 트랜잭션(커넥션)을 얻기 전에 기다리지 않고 판정한다.
 */
@Component
public class ReadPermits {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(1);

    private final Semaphore permits;
    private final Counter rejected;

    public ReadPermits(ReadPathProperties properties, MeterRegistry meters) {
        this.permits = new Semaphore(properties.maxConcurrentPerNode());
        this.rejected = meters.counter("chat.read.rejected", "reason", "overload");
    }

    public <T> T run(Supplier<T> read) {
        if (!permits.tryAcquire()) {
            rejected.increment();
            throw ApiException.unavailable(RETRY_AFTER);
        }
        try {
            return read.get();
        } finally {
            permits.release();
        }
    }
}
