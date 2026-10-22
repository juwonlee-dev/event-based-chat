package com.eventchat.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 띄운 서버에 REST 요청을 보내는 작은 클라이언트. 응답은 상태 코드, JSON 본문, 헤더로 돌려준다.
 * 로그인 레이트 리밋(사용자당 분당 5회)에 걸리지 않도록 사용자별 접근 토큰을 한 번 받아 재사용한다.
 */
public final class Api {

    public static final String PASSWORD = "chat1234!";
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Map<String, Map<String, String>> TOKENS = new ConcurrentHashMap<>();

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Api(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public Response get(String path, String token) {
        return call("GET", path, null, token, Map.of());
    }

    public Response get(String path, String token, Map<String, ?> params) {
        return call("GET", path + query(params), null, token, Map.of());
    }

    public Response post(String path, Object body, String token) {
        return call("POST", path, body, token, Map.of());
    }

    public Response post(String path, Object body, String token, Map<String, String> headers) {
        return call("POST", path, body, token, headers);
    }

    public Response call(String method, String path, Object body, String token, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : JSON.writeValueAsString(body)));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode json = raw == null || raw.isBlank() ? null : JSON.readTree(raw);
            return new Response(response.statusCode(), json, response.headers().map());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " 요청 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 로그인 응답 전체(accessToken, refreshToken ...). 같은 사용자는 한 번만 로그인한다. */
    public Map<String, String> login(String username) {
        return TOKENS.computeIfAbsent(username, u -> freshLogin(u));
    }

    public String token(String username) {
        return login(username).get("accessToken");
    }

    /** 캐시를 거치지 않는 로그인(토큰 폐기·갱신을 다루는 테스트용). */
    public Map<String, String> freshLogin(String username) {
        Response response = post("/auth/login", Map.of("username", username, "password", PASSWORD), null);
        if (response.status() != 200) {
            throw new IllegalStateException(username + " 로그인 실패: " + response);
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        response.body().properties().forEach(e -> tokens.put(e.getKey(), e.getValue().asString()));
        return tokens;
    }

    public UUID userId(String username) {
        return UUID.fromString(get("/users/me", token(username)).body().get("id").asString());
    }

    public static String query(Map<String, ?> params) {
        if (params.isEmpty()) {
            return "";
        }
        return "?" + params.entrySet().stream()
                .flatMap(e -> (e.getValue() instanceof List<?> list ? list : List.of(e.getValue())).stream()
                        .map(v -> encode(e.getKey()) + "=" + encode(String.valueOf(v))))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record Response(int status, JsonNode body, Map<String, List<String>> headers) {

        /** problem+json의 code. 본문이 없으면 null */
        public String code() {
            return body == null || !body.has("code") ? null : body.get("code").asString();
        }

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }

        public String text(String field) {
            return body.get(field).asString();
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }
}
