package com.eventchat.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 로그에 userId·sessionId를 붙인다(traceId·spanId는 Micrometer Tracing이 넣는다).
 * 인증 필터 뒤에 돌도록 기본 순서(가장 낮은 우선순위)를 둔다. 본문과 토큰은 남기지 않는다.
 */
@Component
public class RequestMdcFilter extends OncePerRequestFilter {

    private static final Pattern SESSION_PATH = Pattern.compile("^/sessions/([0-9a-fA-F-]{36})(?:/.*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            MDC.put("userId", authentication.getName());
        }
        Matcher session = SESSION_PATH.matcher(request.getRequestURI());
        if (session.matches()) {
            MDC.put("sessionId", session.group(1));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
            MDC.remove("sessionId");
        }
    }
}
