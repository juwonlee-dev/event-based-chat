package com.eventchat.web.error;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

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

    /** 필드 하나의 검증 실패. 형식은 Bean Validation 오류와 같다. */
    public static ApiException invalid(String field, String reason) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "요청 값이 올바르지 않습니다.",
                Map.of("errors", List.of(Map.of("field", field, "reason", reason))), null);
    }

    public static ApiException rateLimited(Duration retryAfter) {
        return new ApiException(ErrorCode.RATE_LIMITED, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                Map.of("retryAfterMs", retryAfter.toMillis()), retryAfter);
    }

    /**
     * 다시 보내면 성공할 수 있는 DB 오류인가. 잠금·문장 시간 초과(TransientDataAccessException),
     * 커넥션 획득 실패(트랜잭션 시작 실패, DataAccessResourceFailureException)는 503으로 돌려준다.
     */
    public static boolean isTransientDbFailure(Throwable e) {
        return e instanceof TransientDataAccessException
                || e instanceof DataAccessResourceFailureException
                || e instanceof CannotCreateTransactionException;
    }

    public static ApiException unavailable(Duration retryAfter) {
        return new ApiException(ErrorCode.TEMPORARILY_UNAVAILABLE, "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.",
                Map.of("retryAfterMs", retryAfter.toMillis()), retryAfter);
    }
}
