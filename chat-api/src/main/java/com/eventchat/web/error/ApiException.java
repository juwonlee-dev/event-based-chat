package com.eventchat.web.error;

import java.time.Duration;
import java.util.Map;
import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties;
    private final Duration retryAfter;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of(), null);
    }

    public ApiException(ErrorCode code, String detail, Map<String, Object> properties, Duration retryAfter) {
        super(detail);
        this.code = code;
        this.properties = properties;
        this.retryAfter = retryAfter;
    }

    public static ApiException rateLimited(Duration retryAfter) {
        return new ApiException(ErrorCode.RATE_LIMITED, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                Map.of("retryAfterMs", retryAfter.toMillis()), retryAfter);
    }
}
