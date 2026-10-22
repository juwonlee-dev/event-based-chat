package com.eventchat.web.error;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 오류를 RFC 9457 problem+json으로 맞추고 {@code code} 확장 필드를 붙인다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException e) {
        ProblemDetail problem = Problems.of(e.getCode(), e.getMessage());
        e.getProperties().forEach(problem::setProperty);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.getCode().status());
        if (e.getRetryAfter() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, e.getRetryAfter().toSeconds())));
        }
        return response.body(problem);
    }

    /**
     * 다시 보내면 성공할 수 있는 DB 오류: 잠금 대기(lock_timeout)·문장 시간 초과, 커넥션 획득 실패.
     * 같은 멱등 키로 다시 보내면 된다.
     */
    @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class})
    ResponseEntity<ProblemDetail> handleTransientDbFailure(Exception e) {
        return handleApiException(ApiException.unavailable(Duration.ofSeconds(1)));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setDetail("요청 값이 올바르지 않습니다.");
        problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "reason", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            problem.setProperty("code", Problems.defaultCode(statusCode));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }
}
