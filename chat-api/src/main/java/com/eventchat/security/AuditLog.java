package com.eventchat.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 보안 관련 행위를 별도 로거(audit)로 남긴다. 비밀번호·토큰·본문은 남기지 않는다.
 */
@Slf4j(topic = "audit")
@Component
public class AuditLog {

    public void record(String action, String actor, String role, String result, String ip) {
        log.atInfo()
                .addKeyValue("action", action)
                .addKeyValue("actor", actor)
                .addKeyValue("role", role)
                .addKeyValue("result", result)
                .addKeyValue("ip", ip)
                .log("{} {} actor={} ip={}", action, result, actor, ip);
    }

    public void denied(HttpServletRequest request, int status, String code) {
        String actor = request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : null;
        record("ACCESS " + request.getMethod() + " " + request.getRequestURI(), actor, null,
                status + " " + code, request.getRemoteAddr());
    }
}
