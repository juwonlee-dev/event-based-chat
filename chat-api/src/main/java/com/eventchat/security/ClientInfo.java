package com.eventchat.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

public record ClientInfo(String ip, String userAgent) {

    private static final int USER_AGENT_MAX = 255;

    public static ClientInfo from(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null && userAgent.length() > USER_AGENT_MAX) {
            userAgent = userAgent.substring(0, USER_AGENT_MAX);
        }
        return new ClientInfo(request.getRemoteAddr(), userAgent);
    }
}
