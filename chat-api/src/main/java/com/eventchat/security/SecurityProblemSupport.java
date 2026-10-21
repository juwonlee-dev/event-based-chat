package com.eventchat.security;

import com.eventchat.web.error.ErrorCode;
import com.eventchat.web.error.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 필터 단계(컨트롤러 밖)에서 난 401/403도 problem+json으로 응답한다.
 */
@Component
@RequiredArgsConstructor
public class SecurityProblemSupport implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;
    private final AuditLog auditLog;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        ErrorCode code = isExpired(authException) ? ErrorCode.AUTH_TOKEN_EXPIRED : ErrorCode.AUTH_INVALID;
        String detail = code == ErrorCode.AUTH_TOKEN_EXPIRED ? "토큰이 만료되었습니다." : "인증이 필요합니다.";
        auditLog.denied(request, 401, code.name());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, Problems.of(code, detail));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        auditLog.denied(request, 403, ErrorCode.ACCESS_DENIED.name());
        write(response, Problems.of(ErrorCode.ACCESS_DENIED, "접근 권한이 없습니다."));
    }

    private boolean isExpired(AuthenticationException e) {
        return e instanceof OAuth2AuthenticationException oauth2
                && oauth2.getError().getDescription() != null
                && oauth2.getError().getDescription().contains("expired");
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
