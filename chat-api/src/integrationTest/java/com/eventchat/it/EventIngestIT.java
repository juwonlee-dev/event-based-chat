package com.eventchat.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventchat.it.support.IntegrationTest;
import com.eventchat.testsupport.Api.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

@DisplayName("이벤트 수집과 조회")
class EventIngestIT extends IntegrationTest {

    private String a;
    private String b;
    private String admin;
    private String sid;

    /** hospital.user가 만들고 hospital.customer가 참여한 IN_PROGRESS 세션 */
    @BeforeEach
    void session() {
        a = api.token("hospital.user");
        b = api.token("hospital.customer");
        admin = api.token("admin");
        sid = api.post("/sessions", Map.of("title", "이벤트 수집 검증"), a).text("id");
        api.post("/sessions/" + sid + "/join", null, b);
    }

    private Response send(String token, String type, Object payload, String clientEventId) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("payload", payload);
        if (clientEventId != null) {
            body.put("clientEventId", clientEventId);
        }
        return api.post("/sessions/" + sid + "/events", body, token);
    }

    private Response message(String token, String content) {
        return send(token, "MESSAGE", Map.of("content", content), UUID.randomUUID().toString());
    }

    private Response typing(String token, String type) {
        return send(token, type, null, null);
    }

    private Response readUpTo(long upToSeq) {
        return send(b, "READ_CURSOR", Map.of("upToSeq", upToSeq), null);
    }

    private List<JsonNode> events(String token) {
        Response page = api.get("/sessions/" + sid + "/events", token, Map.of("limit", 200));
        assertThat(page.status()).as(page.toString()).isEqualTo(200);
        List<JsonNode> items = new ArrayList<>();
        page.body().get("items").forEach(items::add);
        return items;
    }

    private double suppressed(String type) {
        Response metric = management.get("/actuator/metrics/chat.events.suppressed", null, Map.of("tag", "type:" + type));
        return metric.status() == 200 ? metric.body().get("measurements").get(0).get("value").asDouble() : 0;
    }

    @Test
    @DisplayName("WAITING 세션에는 메시지를 보낼 수 없다")
    void messageInWaiting() {
        String waiting = api.post("/sessions", Map.of("title", "대기 중"), a).text("id");
        Response response = api.post("/sessions/" + waiting + "/events",
                Map.of("type", "MESSAGE", "payload", Map.of("content", "먼저"), "clientEventId", UUID.randomUUID()), a);
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("SESSION_NOT_ACTIVE");
        assertThat(response.text("sessionStatus")).isEqualTo("WAITING");
    }

    @Test
    @DisplayName("메시지는 201로 저장되고, 같은 clientEventId 재전송은 같은 이벤트, 내용이 다르면 409")
    void messageIdempotent() {
        String cid = UUID.randomUUID().toString();
        Response first = send(a, "MESSAGE", Map.of("content", "  안녕하세요  "), cid);
        assertThat(first.status()).isEqualTo(201);
        JsonNode m = first.body();
        assertThat(m.get("type").asString()).isEqualTo("MESSAGE");
        assertThat(m.get("msgNo").asInt()).isEqualTo(1);
        assertThat(m.get("payload").get("content").asString()).isEqualTo("안녕하세요");
        assertThat(m.get("clientEventId").asString()).isEqualTo(cid);
        assertThat(m.get("actorId").asString()).isEqualTo(api.userId("hospital.user").toString());
        assertThat(m.get("serverTime").asString()).endsWith("Z");
        assertThat(m.has("ttlMs")).isFalse();
        assertThat(m.has("schemaVersion")).isFalse();

        Response dup = send(a, "MESSAGE", Map.of("content", "안녕하세요"), cid);
        assertThat(dup.status()).isEqualTo(200);
        assertThat(dup.body().get("seq")).isEqualTo(m.get("seq"));
        assertThat(dup.body().get("eventId")).isEqualTo(m.get("eventId"));
        assertThat(send(a, "MESSAGE", Map.of("content", "다른 내용"), cid).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        api.post("/sessions/" + sid + "/end", null, a);
        Response afterEnd = send(a, "MESSAGE", Map.of("content", "안녕하세요"), cid);
        assertThat(afterEnd.status()).isEqualTo(200);
        assertThat(afterEnd.body().get("seq")).isEqualTo(m.get("seq"));
    }

    @Test
    @DisplayName("메시지 검증: clientEventId 필수, 빈 내용 거부, 2000자(코드 포인트 기준)까지")
    void messageValidation() {
        assertThat(send(a, "MESSAGE", Map.of("content", "x"), null).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(message(a, "   ").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(message(a, "가".repeat(2000)).status()).isEqualTo(201);
        Response tooLong = message(a, "가".repeat(2001));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("MESSAGE_TOO_LONG");
        assertThat(tooLong.body().get("maxLength").asInt()).isEqualTo(2000);
        assertThat(tooLong.body().get("actualLength").asInt()).isEqualTo(2001);
        assertThat(message(a, "😀".repeat(2000)).status()).isEqualTo(201);
    }

    @ParameterizedTest(name = "type={0}")
    @NullSource
    @ValueSource(strings = {"JOIN", "DISCONNECT", "RECONNECT", "SESSION_STATUS_CHANGED", "foo"})
    @DisplayName("클라이언트가 보낼 수 없는 유형은 400 EVENT_TYPE_NOT_ALLOWED")
    void typeNotAllowed(String type) {
        Response response = send(a, type, Map.of(), UUID.randomUUID().toString());
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("EVENT_TYPE_NOT_ALLOWED");
    }

    @Test
    @DisplayName("관리자는 403 ADMIN_READ_ONLY, 참여자가 아니면 403 NOT_PARTICIPANT")
    void permissions() {
        assertThat(message(admin, "관리자").code()).isEqualTo("ADMIN_READ_ONLY");
        assertThat(message(api.token("furniture.user"), "끼어들기").code()).isEqualTo("NOT_PARTICIPANT");
    }

    @Test
    @DisplayName("입력 중 표시: 3초 안의 반복과 이미 꺼진 상태의 중지는 204, 메시지를 보내면 꺼진다")
    void typing() throws InterruptedException {
        Response started = typing(b, "TYPING_STARTED");
        assertThat(started.status()).isEqualTo(201);
        assertThat(started.body().get("ttlMs").asInt()).isEqualTo(10_000);
        assertThat(started.text("subjectId")).isEqualTo(api.userId("hospital.customer").toString());
        assertThat(started.body().has("expiresAt")).isTrue();
        assertThat(typing(b, "TYPING_STARTED").status()).isEqualTo(204);

        Response stopped = typing(b, "TYPING_STOPPED");
        assertThat(stopped.status()).isEqualTo(201);
        assertThat(stopped.body().has("ttlMs")).isFalse();
        assertThat(typing(b, "TYPING_STOPPED").status()).isEqualTo(204);

        assertThat(typing(b, "TYPING_STARTED").status()).isEqualTo(201);
        Thread.sleep(3_100);
        assertThat(typing(b, "TYPING_STARTED").status()).as("3초가 지나면 갱신").isEqualTo(201);
        message(b, "네 말씀하세요");
        assertThat(typing(b, "TYPING_STOPPED").status()).as("메시지가 입력 중을 끈다").isEqualTo(204);

        api.post("/sessions/" + sid + "/end", null, a);
        assertThat(typing(b, "TYPING_STARTED").status()).isEqualTo(409);
    }

    @Test
    @DisplayName("읽음 커서: 1초에 한 번만 저장하고 나머지는 억제하며, 마지막 MESSAGE 위치로 맞춘다")
    void readCursor() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            message(i % 2 == 0 ? a : b, "메시지 " + i);
        }
        long lastSeq = events(a).getLast().get("seq").asLong();
        double before = suppressed("READ_CURSOR");

        List<Response> responses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            responses.add(readUpTo(9999));
        }
        assertThat(responses).extracting(Response::status).containsOnly(202);
        List<Response> stored = responses.stream().filter(r -> r.body() != null).toList();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().body().get("payload").get("upToSeq").asLong()).isEqualTo(lastSeq);
        assertThat(stored.getFirst().body().get("payload").get("readMsgNo").asInt()).isEqualTo(5);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(suppressed("READ_CURSOR") - before).isEqualTo(9));

        message(a, "새 메시지");
        Response within = readUpTo(9999);
        assertThat(within.status()).isEqualTo(202);
        assertThat(within.body()).as("1초 안이면 저장하지 않는다").isNull();
        Thread.sleep(1_100);
        Response later = readUpTo(9999);
        assertThat(later.body().get("payload").get("readMsgNo").asInt()).isEqualTo(6);
        assertThat(readUpTo(-1).status()).isEqualTo(400);

        Thread.sleep(1_100);
        assertThat(readUpTo(9999).body()).as("커서 뒤에 메시지가 없으면(자기 READ_CURSOR만 있으면) 저장하지 않는다").isNull();

        List<JsonNode> items = events(a);
        JsonNode lastMessage = items.stream().filter(i -> i.get("type").asString().equals("MESSAGE")).toList().getLast();
        JsonNode lastRead = items.stream().filter(i -> i.get("type").asString().equals("READ_CURSOR")).toList().getLast();
        assertThat(lastRead.get("payload").get("upToSeq")).isEqualTo(lastMessage.get("seq"));
        assertThat(lastRead.get("payload").get("readMsgNo")).isEqualTo(lastMessage.get("msgNo"));
    }

    @Test
    @DisplayName("참여자 조회: seq가 1부터 빈틈없고, afterSeq로 나눠 읽어도 같다")
    void participantRead() {
        for (int i = 0; i < 7; i++) {
            message(a, "메시지 " + i);
        }
        Response page = api.get("/sessions/" + sid + "/events", a, Map.of("limit", 200));
        List<Long> seqs = page.body().get("items").valueStream().map(i -> i.get("seq").asLong()).toList();
        assertThat(seqs).containsExactlyElementsOf(LongStream.rangeClosed(1, seqs.size()).boxed().toList());
        assertThat(page.body().get("hasMore").asBoolean()).isFalse();
        assertThat(page.body().get("nextAfterSeq").asLong()).isEqualTo(seqs.getLast());
        page.body().get("items").forEach(i -> assertThat(i.has("schemaVersion")).isFalse());

        List<Long> paged = new ArrayList<>();
        long after = 0;
        boolean more = true;
        while (more) {
            Response p = api.get("/sessions/" + sid + "/events", b, Map.of("afterSeq", after, "limit", 3));
            p.body().get("items").forEach(i -> paged.add(i.get("seq").asLong()));
            after = p.body().get("nextAfterSeq").asLong();
            more = p.body().get("hasMore").asBoolean();
        }
        assertThat(paged).isEqualTo(seqs);

        Response from = api.get("/sessions/" + sid + "/events", a, Map.of("from", "2026-01-01T00:00:00Z"));
        assertThat(from.status()).isEqualTo(403);
        assertThat(from.code()).isEqualTo("ADMIN_ONLY");
        assertThat(api.get("/sessions/" + sid + "/events", a, Map.of("limit", 201)).status()).isEqualTo(400);
        assertThat(api.get("/sessions/" + sid + "/events", api.token("furniture.user")).code())
                .isEqualTo("NOT_PARTICIPANT");
    }

    @Test
    @DisplayName("관리자 조회: from/to는 양 끝 포함, 유형 필터, 시각 형식 검증, limit 500까지")
    void adminRead() {
        for (int i = 0; i < 6; i++) {
            message(a, "메시지 " + i);
            readUpTo(9999);
        }
        List<JsonNode> items = events(a);
        JsonNode start = items.get(2);
        JsonNode mid = items.get(items.size() / 2);
        Response window = api.get("/sessions/" + sid + "/events", admin, Map.of(
                "from", start.get("serverTime").asString(), "to", mid.get("serverTime").asString(), "limit", 500));
        assertThat(window.status()).isEqualTo(200);
        List<JsonNode> w = window.body().get("items").valueStream().toList();
        assertThat(w).isNotEmpty();
        assertThat(w.getFirst().get("serverTime").asString()).isGreaterThanOrEqualTo(start.get("serverTime").asString());
        assertThat(w.getLast().get("serverTime").asString()).isLessThanOrEqualTo(mid.get("serverTime").asString());
        assertThat(w).extracting(i -> i.get("seq").asLong()).contains(mid.get("seq").asLong());
        assertThat(w).allSatisfy(i -> assertThat(i.get("schemaVersion").asInt()).isEqualTo(1));

        Response types = api.get("/sessions/" + sid + "/events", admin, Map.of("types", List.of("MESSAGE", "READ_CURSOR")));
        assertThat(types.body().get("items").valueStream().map(i -> i.get("type").asString()).distinct())
                .containsExactlyInAnyOrder("MESSAGE", "READ_CURSOR");

        assertThat(api.get("/sessions/" + sid + "/events", admin, Map.of("from", "yesterday")).code())
                .isEqualTo("INVALID_TIME_FORMAT");
        assertThat(api.get("/sessions/" + sid + "/events", admin,
                Map.of("from", "2026-10-22T00:00:00Z", "to", "2026-10-21T00:00:00Z")).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.get("/sessions/" + sid + "/events", admin, Map.of("limit", 500)).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("종료 후에는 새 이벤트가 409이고, 이벤트 조회는 그대로 된다")
    void afterEnd() {
        api.post("/sessions/" + sid + "/end", null, a);
        Response response = message(a, "종료 후");
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("SESSION_NOT_ACTIVE");
        assertThat(response.text("sessionStatus")).isEqualTo("COMPLETED");
        assertThat(api.get("/sessions/" + sid + "/events", b).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("퇴장한 참여자는 이벤트를 볼 수 없고, 남은 참여자는 SUSPENDED 전이까지 본다")
    void leaverCannotRead() {
        api.post("/sessions/" + sid + "/leave", null, b);
        Response leaver = api.get("/sessions/" + sid + "/events", b);
        assertThat(leaver.status()).isEqualTo(403);
        assertThat(leaver.code()).isEqualTo("PARTICIPANT_LEFT");
        assertThat(events(a).getLast().get("payload").get("to").asString()).isEqualTo("SUSPENDED");
    }
}
