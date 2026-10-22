package com.eventchat.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventchat.it.support.IntegrationTest;
import com.eventchat.it.support.OwnerDb;
import com.eventchat.testsupport.Api.Response;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("세션 생명주기")
class SessionLifecycleIT extends IntegrationTest {

    private String a;
    private String b;
    private String c;
    private String admin;

    @BeforeEach
    void users() {
        a = api.token("insurance.user");
        b = api.token("bank.customer");
        c = api.token("hospital.user");
        admin = api.token("admin");
    }

    private static Map<String, String> key(String key) {
        return Map.of("Idempotency-Key", key);
    }

    private String create(String title, String token) {
        Response response = api.post("/sessions", Map.of("title", title), token);
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return response.text("id");
    }

    private static List<String> states(Response response) {
        return response.body().get("participants").valueStream().map(p -> p.get("state").asString()).toList();
    }

    @Test
    @DisplayName("생성하면 WAITING이고, 같은 키로 다시 보내면 같은 세션, 본문이 다르면 409")
    void createIdempotent() {
        String k = UUID.randomUUID().toString();
        Response created = api.post("/sessions", Map.of("title", "  보험 상담 테스트  "), a, key(k));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("status")).isEqualTo("WAITING");
        assertThat(created.text("title")).isEqualTo("보험 상담 테스트");
        assertThat(created.text("category")).isEqualTo("INSURANCE");
        assertThat(created.body().get("lastSeq").asLong()).isEqualTo(2);
        assertThat(created.body().get("participants")).hasSize(1);
        assertThat(created.body().get("participants").get(0).get("userId").asString())
                .isEqualTo(api.userId("insurance.user").toString());
        assertThat(states(created)).containsExactly("JOINED");

        Response again = api.post("/sessions", Map.of("title", "보험 상담 테스트"), a, key(k));
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.text("id")).isEqualTo(created.text("id"));

        Response other = api.post("/sessions", Map.of("title", "다른 제목"), a, key(k));
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    @DisplayName("관리자는 쓰기 요청에서 403이 먼저, 없는 세션은 404")
    void adminReadOnlyBeforeNotFound() {
        assertThat(api.post("/sessions", Map.of("title", "관리자"), admin).code()).isEqualTo("ADMIN_READ_ONLY");
        Response adminJoin = api.post("/sessions/" + UUID.randomUUID() + "/join", null, admin);
        assertThat(adminJoin.status()).isEqualTo(403);
        assertThat(adminJoin.code()).isEqualTo("ADMIN_READ_ONLY");
        Response join = api.post("/sessions/" + UUID.randomUUID() + "/join", null, b);
        assertThat(join.status()).isEqualTo(404);
        assertThat(join.code()).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    @DisplayName("WAITING에서는 종료할 수 없고, 생성자는 다시 참여할 수 없으며, 상세는 누구나 본다")
    void waitingRules() {
        String sid = create("대기 규칙", a);
        Response end = api.post("/sessions/" + sid + "/end", null, a);
        assertThat(end.status()).isEqualTo(409);
        assertThat(end.code()).isEqualTo("SESSION_NOT_STARTED");
        assertThat(end.text("sessionStatus")).isEqualTo("WAITING");
        assertThat(api.post("/sessions/" + sid + "/join", null, a).code()).isEqualTo("ALREADY_JOINED");
        Response leave = api.post("/sessions/" + sid + "/leave", null, b);
        assertThat(leave.status()).isEqualTo(403);
        assertThat(leave.code()).isEqualTo("NOT_PARTICIPANT");
        assertThat(api.get("/sessions/" + sid, c).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("두 사람이 동시에 참여하면 한 명만 성공하고 IN_PROGRESS가 된다")
    void concurrentJoin() {
        String sid = create("동시 참여", a);
        List<String> tokens = List.of(b, c);
        List<Response> responses = concurrently(2, i -> api.post("/sessions/" + sid + "/join", null, tokens.get(i)));
        assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(200, 409);

        int winner = responses.get(0).status() == 200 ? 0 : 1;
        Response won = responses.get(winner);
        Response lost = responses.get(1 - winner);
        assertThat(lost.code()).isEqualTo("SESSION_NOT_JOINABLE");
        assertThat(lost.text("sessionStatus")).isEqualTo("IN_PROGRESS");
        assertThat(won.text("status")).isEqualTo("IN_PROGRESS");
        assertThat(won.body().get("startedAt").isNull()).isFalse();
        assertThat(won.body().get("participants")).hasSize(2);
        assertThat(won.body().get("lastSeq").asLong()).isEqualTo(4);

        Response outsider = api.get("/sessions/" + sid, tokens.get(1 - winner));
        assertThat(outsider.status()).isEqualTo(403);
        assertThat(outsider.code()).isEqualTo("NOT_PARTICIPANT");
        assertThat(api.get("/sessions/" + sid, admin).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("퇴장하면 SUSPENDED, 같은 키 재시도는 같은 결과, 이후 종료·참여는 409")
    void leave() {
        String sid = create("퇴장", a);
        api.post("/sessions/" + sid + "/join", null, b);
        String k = UUID.randomUUID().toString();

        Response left = api.post("/sessions/" + sid + "/leave", null, b, key(k));
        assertThat(left.status()).isEqualTo(200);
        assertThat(left.text("status")).isEqualTo("SUSPENDED");
        assertThat(left.text("endReason")).isEqualTo("LEFT");
        assertThat(states(left)).containsExactly("JOINED", "LEFT");

        Response retry = api.post("/sessions/" + sid + "/leave", null, b, key(k));
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().get("lastSeq").asLong()).isEqualTo(6);

        assertThat(api.post("/sessions/" + sid + "/leave", null, b).code()).isEqualTo("PARTICIPANT_LEFT");
        Response leaverDetail = api.get("/sessions/" + sid, b);
        assertThat(leaverDetail.status()).isEqualTo(403);
        assertThat(leaverDetail.code()).isEqualTo("PARTICIPANT_LEFT");
        assertThat(api.get("/sessions/" + sid, a).text("status")).isEqualTo("SUSPENDED");

        Response end = api.post("/sessions/" + sid + "/end", null, a);
        assertThat(end.code()).isEqualTo("SESSION_NOT_ACTIVE");
        assertThat(end.text("sessionStatus")).isEqualTo("SUSPENDED");
        assertThat(api.post("/sessions/" + sid + "/join", null, c).code()).isEqualTo("SESSION_NOT_JOINABLE");
    }

    @Test
    @DisplayName("종료하면 COMPLETED, 같은 키를 다른 명령에 쓰면 409, 상세는 두 참여자 모두 본다")
    void end() {
        Response created = api.post("/sessions", Map.of("title", "은행 대출 문의"), b);
        assertThat(created.text("category")).isEqualTo("BANK");
        String sid = created.text("id");
        api.post("/sessions/" + sid + "/join", null, c);
        String k = UUID.randomUUID().toString();

        Response ended = api.post("/sessions/" + sid + "/end", null, c, key(k));
        assertThat(ended.status()).isEqualTo(200);
        assertThat(ended.text("status")).isEqualTo("COMPLETED");
        assertThat(ended.text("endReason")).isEqualTo("ENDED");
        assertThat(ended.body().get("endedAt").isNull()).isFalse();

        assertThat(api.post("/sessions/" + sid + "/end", null, c, key(k)).status()).isEqualTo(200);
        assertThat(api.post("/sessions/" + sid + "/leave", null, c, key(k)).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(api.post("/sessions/" + sid + "/end", null, b).code()).isEqualTo("SESSION_NOT_ACTIVE");
        assertThat(api.get("/sessions/" + sid, b).status()).isEqualTo(200);
        assertThat(api.get("/sessions/" + sid, c).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("WAITING에서 생성자가 나가면 SUSPENDED이고 관리자만 볼 수 있다")
    void creatorLeavesWaiting() {
        String sid = create("혼자 나가기", c);
        Response left = api.post("/sessions/" + sid + "/leave", null, c);
        assertThat(left.text("status")).isEqualTo("SUSPENDED");
        assertThat(states(left)).containsExactly("LEFT");
        assertThat(api.get("/sessions/" + sid, c).code()).isEqualTo("PARTICIPANT_LEFT");
        assertThat(api.get("/sessions/" + sid, admin).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("같은 키로 동시에 생성해도 세션은 하나다")
    void concurrentCreateSameKey() {
        String k = UUID.randomUUID().toString();
        List<Response> responses = concurrently(4, i -> api.post("/sessions", Map.of("title", "동시 생성"), a, key(k)));
        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).extracting(r -> r.text("id")).containsOnly(responses.getFirst().text("id"));
    }

    @Test
    @DisplayName("제목은 공백 제외 1~100자, 멱등 키는 64자 이하, 세션 ID는 UUID")
    void validation() {
        assertThat(api.post("/sessions", Map.of("title", "   "), a).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.post("/sessions", Map.of("title", "가".repeat(101)), a).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.post("/sessions", Map.of("title", "가".repeat(100)), a).status()).isEqualTo(201);
        assertThat(api.post("/sessions", Map.of("title", "x"), a, key("k".repeat(65))).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(api.get("/sessions/not-a-uuid", a).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("앱 계정은 이벤트 테이블을 고치거나 지울 수 없다")
    void eventTableIsAppendOnly() {
        create("권한 확인", a);
        assertThat(OwnerDb.scalar("SELECT count(*) FROM session_event")).isNotEqualTo("0");
        assertThatThrownBy(() -> OwnerDb.asApp("UPDATE session_event SET schema_version = 1"))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> OwnerDb.asApp("DELETE FROM session_event"))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> OwnerDb.asApp("TRUNCATE session_event"))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
    }
}
