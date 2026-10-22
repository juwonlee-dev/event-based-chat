package com.eventchat.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventchat.it.support.IntegrationTest;
import com.eventchat.testsupport.Api.Response;
import com.eventchat.testsupport.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인증")
class AuthIT extends IntegrationTest {

    private static final String USER = "customer-center.user";

    @Test
    @DisplayName("로그인하면 접근·갱신 토큰을 받고 /users/me로 내 정보를 본다")
    void loginAndMe() {
        Map<String, String> tokens = api.freshLogin(USER);
        assertThat(tokens.get("tokenType")).isEqualTo("Bearer");
        assertThat(tokens).containsKeys("accessToken", "refreshToken");

        Response me = api.get("/users/me", tokens.get("accessToken"));
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.text("username")).isEqualTo(USER);
        assertThat(me.text("role")).isEqualTo("USER");
        assertThat(me.text("category")).isEqualTo("CUSTOMER_CENTER");
        assertThat(me.body().get("maxMessageLength").asInt()).isEqualTo(2000);
    }

    @Test
    @DisplayName("비밀번호가 틀려도, 없는 사용자여도 같은 401 problem+json(AUTH_INVALID)")
    void wrongCredentials() {
        Response wrong = api.post("/auth/login", Map.of("username", "bank.user", "password", "wrong"), null);
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.code()).isEqualTo("AUTH_INVALID");
        assertThat(wrong.header("Content-Type")).startsWith("application/problem+json");

        Response unknown = api.post("/auth/login", Map.of("username", "nobody", "password", "x"), null);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(unknown.code()).isEqualTo("AUTH_INVALID");
    }

    @Test
    @DisplayName("토큰이 없거나 위조되면 AUTH_INVALID, 만료되면 AUTH_TOKEN_EXPIRED")
    void accessTokenChecks() {
        Response none = api.get("/users/me", null);
        assertThat(none.status()).isEqualTo(401);
        assertThat(none.code()).isEqualTo("AUTH_INVALID");
        assertThat(none.header("WWW-Authenticate")).isEqualTo("Bearer");

        String token = api.token(USER);
        assertThat(api.get("/users/me", token.substring(0, token.length() - 3) + "abc").code()).isEqualTo("AUTH_INVALID");

        String expired = TestTokens.forge(JWT_SECRET, api.userId(USER), "USER", -300);
        Response response = api.get("/users/me", expired);
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("AUTH_TOKEN_EXPIRED");
    }

    @Test
    @DisplayName("빈 값은 400 VALIDATION_FAILED와 필드별 오류")
    void validation() {
        Response response = api.post("/auth/login", Map.of("username", "", "password", ""), null);
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.body().get("errors")).hasSize(2);
    }

    @Test
    @DisplayName("갱신하면 갱신 토큰이 바뀌고, 쓴 토큰을 다시 쓰면 같은 계열 전체가 폐기된다")
    void refreshRotationAndReuse() {
        String first = api.freshLogin(USER).get("refreshToken");
        Response rotated = api.post("/auth/refresh", Map.of("refreshToken", first), null);
        assertThat(rotated.status()).isEqualTo(200);
        String second = rotated.text("refreshToken");
        assertThat(second).isNotEqualTo(first);

        Response reused = api.post("/auth/refresh", Map.of("refreshToken", first), null);
        assertThat(reused.status()).isEqualTo(401);
        assertThat(reused.code()).isEqualTo("REFRESH_TOKEN_REUSED");
        assertThat(api.post("/auth/refresh", Map.of("refreshToken", second), null).code())
                .isEqualTo("REFRESH_TOKEN_INVALID");
        assertThat(api.post("/auth/refresh", Map.of("refreshToken", "garbage"), null).code())
                .isEqualTo("REFRESH_TOKEN_INVALID");
    }

    @Test
    @DisplayName("전체 로그아웃 뒤에는 그 사용자의 갱신 토큰을 쓸 수 없다")
    void logoutAll() {
        Map<String, String> tokens = api.freshLogin("customer-center.customer");
        assertThat(api.post("/auth/logout", Map.of("all", true), tokens.get("accessToken")).status()).isEqualTo(204);
        assertThat(api.post("/auth/refresh", Map.of("refreshToken", tokens.get("refreshToken")), null).code())
                .isEqualTo("REFRESH_TOKEN_INVALID");
    }

    @Test
    @DisplayName("관리자 토큰은 ADMIN 역할이고 분류가 없다")
    void admin() {
        Response me = api.get("/users/me", api.token("admin"));
        assertThat(me.text("role")).isEqualTo("ADMIN");
        assertThat(me.body().get("category").isNull()).isTrue();
    }

    @Test
    @DisplayName("OpenAPI 문서는 인증 없이 열리고 Bearer 인증 방식이 적혀 있다")
    void openApi() {
        Response docs = api.get("/v3/api-docs", null);
        assertThat(docs.status()).isEqualTo(200);
        assertThat(docs.body().get("paths").has("/auth/login")).isTrue();
        assertThat(docs.body().get("components").get("securitySchemes").has("bearerAuth")).isTrue();
    }

    @Test
    @DisplayName("같은 IP·아이디로 1분에 5번을 넘게 시도하면 429와 Retry-After")
    void loginRateLimit() {
        List<Integer> codes = new ArrayList<>();
        Response last = null;
        for (int i = 0; i < 6; i++) {
            last = api.post("/auth/login", Map.of("username", "real-estate.customer", "password", "bad"), null);
            codes.add(last.status());
        }
        assertThat(codes).containsExactly(401, 401, 401, 401, 401, 429);
        assertThat(last.code()).isEqualTo("RATE_LIMITED");
        assertThat(last.header("Retry-After")).isNotNull();
        assertThat(last.body().get("retryAfterMs").asLong()).isEqualTo(60_000);
    }
}
