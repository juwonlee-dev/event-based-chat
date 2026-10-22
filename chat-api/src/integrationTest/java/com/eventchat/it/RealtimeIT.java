package com.eventchat.it;

import static com.eventchat.testsupport.StompClient.onSubscription;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventchat.it.support.IntegrationTest;
import com.eventchat.testsupport.Api.Response;
import com.eventchat.testsupport.StompClient;
import com.eventchat.testsupport.StompClient.Frame;
import com.eventchat.testsupport.TestTokens;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@DisplayName("실시간(STOMP)")
class RealtimeIT extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration SHORT = Duration.ofMillis(500);

    private final List<StompClient> clients = new ArrayList<>();
    private String a;
    private String b;
    private String c;

    @BeforeEach
    void users() {
        a = api.token("car-sales.user");
        b = api.token("car-sales.customer");
        c = api.token("real-estate.user");
    }

    @AfterEach
    void closeClients() {
        clients.forEach(StompClient::close);
    }

    private String newSession(String title, boolean join) {
        String sid = api.post("/sessions", Map.of("title", title), a).text("id");
        if (join) {
            api.post("/sessions/" + sid + "/join", null, b);
        }
        return sid;
    }

    private StompClient connect(String token, String heartbeat) {
        StompClient client = StompClient.connect(wsUrl(), token, heartbeat, Map.of());
        clients.add(client);
        return client;
    }

    /** 영수증·시스템 큐를 구독하고, sid가 있으면 세션 토픽도 s1으로 구독한다. */
    private StompClient user(String token, String sid, Map<String, String> extra) {
        StompClient client = connect(token, "1000,10000");
        assertThat(client.isConnected()).as(String.valueOf(client.connected())).isTrue();
        client.subscribe("/user/queue/receipts", "receipts");
        client.subscribe("/user/queue/system", "system");
        if (sid != null) {
            client.subscribe(topic(sid), "s1", extra);
        }
        pause(300);
        return client;
    }

    private StompClient user(String token, String sid) {
        return user(token, sid, Map.of());
    }

    private static Map<String, Object> message(String content, String clientEventId) {
        return Map.of("type", "MESSAGE", "clientEventId", clientEventId, "payload", Map.of("content", content));
    }

    private static Predicate<Frame> receipt(Predicate<JsonNode> body) {
        return onSubscription("receipts", body);
    }

    private static Predicate<Frame> error() {
        return f -> f.command().equals("ERROR");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("보낸 사람은 영수증을, 두 참여자는 토픽으로 이벤트를 받는다. 같은 clientEventId는 DUPLICATE로 다시 발행된다")
    void sendAndReceive() {
        String sid = newSession("실시간 송수신", true);
        StompClient ca = user(a, sid);
        StompClient cb = user(b, sid);
        assertThat(ca.connected().headers().get("heart-beat")).isEqualTo("10000,1000");

        String cid = UUID.randomUUID().toString();
        ca.send(app(sid), message("안녕하세요, 실시간입니다", cid));
        Frame r = ca.expect(receipt(j -> cid.equals(j.path("clientEventId").asString(null))), WAIT);
        assertThat(r).isNotNull();
        assertThat(r.json().get("kind").asString()).isEqualTo("EVENT_RESULT");
        assertThat(r.json().get("status").asString()).isEqualTo("ACCEPTED");
        long seq = r.json().get("seq").asLong();

        Frame onB = cb.expect(onSubscription("s1", j -> cid.equals(j.path("clientEventId").asString(null))), WAIT);
        assertThat(onB.json().get("payload").get("content").asString()).isEqualTo("안녕하세요, 실시간입니다");
        assertThat(onB.json().get("seq").asLong()).isEqualTo(seq);
        assertThat(ca.expect(onSubscription("s1", j -> cid.equals(j.path("clientEventId").asString(null))), WAIT))
                .as("보낸 사람도 토픽으로 받는다").isNotNull();
        assertThat(cb.expect(receipt(j -> true), SHORT)).as("영수증은 보낸 사람에게만").isNull();

        ca.send(app(sid), message("안녕하세요, 실시간입니다", cid));
        Frame dup = ca.expect(receipt(j -> cid.equals(j.path("clientEventId").asString(null))), WAIT);
        assertThat(dup.json().get("status").asString()).isEqualTo("DUPLICATE");
        assertThat(dup.json().get("seq").asLong()).isEqualTo(seq);
        Frame again = cb.expect(onSubscription("s1", j -> cid.equals(j.path("clientEventId").asString(null))), WAIT);
        assertThat(again.json().get("seq").asLong()).isEqualTo(seq);
    }

    @Test
    @DisplayName("입력 중 이벤트와 REST로 보낸 메시지도 토픽으로 전달된다")
    void typingAndRestDelivered() {
        String sid = newSession("입력 중", true);
        StompClient ca = user(a, sid);
        StompClient cb = user(b, sid);

        cb.send(app(sid), Map.of("type", "TYPING_STARTED"));
        Frame typing = ca.expect(onSubscription("s1", j -> "TYPING_STARTED".equals(j.path("type").asString(null))), WAIT);
        assertThat(typing.json().get("ttlMs").asInt()).isEqualTo(10_000);
        assertThat(typing.json().get("subjectId").asString()).isEqualTo(api.userId("car-sales.customer").toString());
        assertThat(cb.expect(receipt(j -> true), SHORT)).as("받아들인 입력 중 이벤트에는 영수증이 없다").isNull();

        Response rest = api.post("/sessions/" + sid + "/events", message("REST로 보낸 메시지", UUID.randomUUID().toString()), a);
        assertThat(rest.status()).isEqualTo(201);
        long seq = rest.body().get("seq").asLong();
        assertThat(cb.expect(onSubscription("s1", j -> j.path("seq").asLong() == seq), WAIT)).isNotNull();
    }

    @Test
    @DisplayName("너무 긴 메시지와 깨진 본문은 REJECTED 영수증으로 거부하고 연결은 유지한다")
    void rejectedButConnected() {
        String sid = newSession("거부", true);
        StompClient ca = user(a, sid);

        String longCid = UUID.randomUUID().toString();
        ca.send(app(sid), message("가".repeat(7000), longCid)); // 약 21KB
        Frame r = ca.expect(receipt(j -> longCid.equals(j.path("clientEventId").asString(null))), WAIT);
        assertThat(r.json().get("status").asString()).isEqualTo("REJECTED");
        JsonNode error = r.json().get("error");
        assertThat(error.get("code").asString()).isEqualTo("MESSAGE_TOO_LONG");
        assertThat(error.get("status").asInt()).isEqualTo(400);
        assertThat(error.get("actualLength").asInt()).isEqualTo(7000);

        String okCid = UUID.randomUUID().toString();
        ca.send(app(sid), message("긴 글 뒤에도 연결 유지", okCid));
        assertThat(ca.expect(receipt(j -> okCid.equals(j.path("clientEventId").asString(null))), WAIT)
                .json().get("status").asString()).isEqualTo("ACCEPTED");

        ca.frame("SEND", Map.of("destination", app(sid), "content-type", "application/json"), "{not json");
        Frame bad = ca.expect(receipt(j -> "REJECTED".equals(j.path("status").asString(null))), WAIT);
        assertThat(bad.json().path("clientEventId").asString(null)).as("본문을 읽지 못했으니 clientEventId가 없다").isNull();
        assertThat(bad.json().get("error").get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(ca.isClosed()).isFalse();
    }

    @Test
    @DisplayName("종료된 세션이나 참여하지 않은 세션 구독은 SUBSCRIBE_REJECTED이고 연결은 유지된다")
    void subscribeRejected() {
        String sid = newSession("구독 거부", true);
        String ended = newSession("종료된 세션", true);
        api.post("/sessions/" + ended + "/end", null, a);
        StompClient ca = user(a, sid);

        ca.subscribe(topic(ended), "s2");
        Frame r = ca.expect(receipt(j -> "SUBSCRIBE_REJECTED".equals(j.path("kind").asString(null))), WAIT);
        assertThat(r.json().get("code").asString()).isEqualTo("SESSION_NOT_ACTIVE");
        assertThat(r.json().get("sessionStatus").asString()).isEqualTo("COMPLETED");
        assertThat(r.json().get("lastSeq").asLong()).isEqualTo(5);
        assertThat(r.json().get("sessionId").asString()).isEqualTo(ended);

        String probe = UUID.randomUUID().toString();
        ca.send(app(sid), message("거부 뒤에도 연결 유지", probe));
        assertThat(ca.expect(receipt(j -> probe.equals(j.path("clientEventId").asString(null))), WAIT)
                .json().get("status").asString()).isEqualTo("ACCEPTED");

        StompClient cc = user(c, null);
        cc.subscribe(topic(sid), "x");
        assertThat(cc.expect(receipt(j -> "SUBSCRIBE_REJECTED".equals(j.path("kind").asString(null))), WAIT)
                .json().get("code").asString()).isEqualTo("NOT_PARTICIPANT");
        cc.send(app(sid), message("끼어들기", UUID.randomUUID().toString()));
        Frame rejected = cc.expect(receipt(j -> "REJECTED".equals(j.path("status").asString(null))), WAIT);
        assertThat(rejected.json().get("error").get("code").asString()).isEqualTo("NOT_PARTICIPANT");
        assertThat(cc.isClosed()).isFalse();
    }

    @Test
    @DisplayName("허용 목록 밖 목적지(토픽으로 SEND, 임의 토픽, 대문자 UUID)는 ERROR FORBIDDEN_DESTINATION 후 연결을 끊는다")
    void forbiddenDestinations() {
        String sid = newSession("목적지", true);
        StompClient cb = user(b, sid);

        StompClient forger = user(c, null);
        forger.send(topic(sid), Map.of("seq", 999, "type", "MESSAGE", "payload", Map.of("content", "위조")));
        assertThat(forger.expect(error(), WAIT).message()).isEqualTo("FORBIDDEN_DESTINATION");
        assertThat(forger.awaitClosed(WAIT)).isTrue();
        assertThat(cb.expect(onSubscription("s1", j -> j.path("seq").asLong() == 999), Duration.ofSeconds(1)))
                .as("위조 프레임은 전달되지 않는다").isNull();

        StompClient other = user(c, null);
        other.subscribe("/topic/anything", "y");
        assertThat(other.expect(error(), WAIT).message()).isEqualTo("FORBIDDEN_DESTINATION");
        assertThat(other.awaitClosed(WAIT)).isTrue();

        StompClient upper = user(a, null);
        upper.subscribe("/topic/sessions/" + sid.toUpperCase(), "upper");
        assertThat(upper.expect(error(), WAIT).message()).isEqualTo("FORBIDDEN_DESTINATION");
        assertThat(upper.awaitClosed(WAIT)).isTrue();
    }

    @Test
    @DisplayName("CONNECT 검사: 토큰 없음·위조·만료, heart-beat 미요청·과다, 관리자")
    void connectChecks() {
        Map<String, StompClient> attempts = Map.of(
                "AUTH_INVALID/none", connect(null, "1000,10000"),
                "AUTH_INVALID/bad", connect(a.substring(0, a.length() - 3) + "abc", "1000,10000"),
                "AUTH_TOKEN_EXPIRED", connect(TestTokens.forge(JWT_SECRET, api.userId("car-sales.user"), "USER", -120),
                        "1000,10000"),
                "HEARTBEAT_REQUIRED/0", connect(a, "0,0"),
                "HEARTBEAT_REQUIRED/30s", connect(a, "30000,10000"),
                "ADMIN_READ_ONLY", connect(api.token("admin"), "1000,10000"));
        attempts.forEach((expected, client) -> {
            assertThat(client.connected()).as(expected).isNotNull();
            assertThat(client.connected().command()).as(expected).isEqualTo("ERROR");
            assertThat(client.connected().message()).as(expected).isEqualTo(expected.split("/")[0]);
            assertThat(client.awaitClosed(WAIT)).as(expected).isTrue();
        });
    }

    @Test
    @DisplayName("한 사용자는 노드당 5개까지 연결하고, 하나를 닫으면 다시 연결할 수 있다")
    void connectionLimit() {
        pause(500); // 앞 테스트가 닫은 연결이 정리될 시간
        List<StompClient> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(connect(c, "1000,10000"));
        }
        assertThat(five).allSatisfy(x -> assertThat(x.isConnected()).isTrue());
        StompClient sixth = connect(c, "1000,10000");
        assertThat(sixth.connected().message()).isEqualTo("TOO_MANY_CONNECTIONS");

        five.forEach(StompClient::close);
        pause(500);
        assertThat(connect(c, "1000,10000").isConnected()).isTrue();
    }

    @Test
    @DisplayName("접근 토큰이 만료되면 AUTH_TOKEN_EXPIRED로 끊고(DISCONNECT 이벤트는 남지 않는다), 전체 로그아웃은 AUTH_REVOKED")
    void tokenExpiryAndRevocation() {
        Map<String, String> login = api.freshLogin("furniture.customer");
        UUID customer = api.userId("furniture.customer");
        String sid = api.post("/sessions", Map.of("title", "토큰 만료"), a).text("id");
        api.post("/sessions/" + sid + "/join", null, login.get("accessToken"));
        StompClient ca = user(a, sid);
        StompClient original = user(login.get("accessToken"), sid);

        StompClient shortLived = user(TestTokens.forge(JWT_SECRET, customer, "CUSTOMER", 4), sid);
        long mark = api.get("/sessions/" + sid + "/events", a, Map.of("limit", 200)).body().get("nextAfterSeq").asLong();
        Frame expired = shortLived.expect(error(), Duration.ofSeconds(8));
        assertThat(expired.message()).isEqualTo("AUTH_TOKEN_EXPIRED");
        assertThat(shortLived.awaitClosed(WAIT)).isTrue();

        Response refreshed = api.post("/auth/refresh", Map.of("refreshToken", login.get("refreshToken")), null);
        assertThat(refreshed.status()).isEqualTo(200);
        StompClient reconnected = user(refreshed.text("accessToken"), sid, Map.of("resume-after-seq", "0"));
        pause(5_000); // presence 유예(3초)와 스위퍼가 지나도
        Response after = api.get("/sessions/" + sid + "/events", a, Map.of("afterSeq", mark, "limit", 200));
        assertThat(after.body().get("items").valueStream().map(i -> i.get("type").asString())).doesNotContain("DISCONNECT");

        assertThat(api.post("/auth/logout", Map.of("all", true), refreshed.text("accessToken")).status()).isEqualTo(204);
        for (StompClient client : List.of(reconnected, original)) {
            assertThat(client.expect(error(), Duration.ofSeconds(3)).message()).isEqualTo("AUTH_REVOKED");
            assertThat(client.awaitClosed(WAIT)).isTrue();
        }
        assertThat(ca.isClosed()).as("다른 사용자 연결은 그대로").isFalse();
    }

    @Test
    @DisplayName("같은 멱등 키로 퇴장을 다시 보내면 LEAVE와 상태 전이를 같은 seq로 다시 발행한다")
    void idempotentLeaveRepublishes() {
        String sid = api.post("/sessions", Map.of("title", "퇴장 재전송"), a).text("id");
        api.post("/sessions/" + sid + "/join", null, c);
        StompClient ca = user(a, sid);
        Map<String, String> key = Map.of("Idempotency-Key", UUID.randomUUID().toString());

        assertThat(api.post("/sessions/" + sid + "/leave", null, c, key).status()).isEqualTo(200);
        List<JsonNode> first = List.of(ca.expect(onSubscription("s1"), WAIT).json(), ca.expect(onSubscription("s1"), WAIT).json());
        assertThat(api.post("/sessions/" + sid + "/leave", null, c, key).status()).isEqualTo(200);
        List<JsonNode> again = List.of(ca.expect(onSubscription("s1"), WAIT).json(), ca.expect(onSubscription("s1"), WAIT).json());

        assertThat(first).extracting(j -> j.get("type").asString()).containsExactly("LEAVE", "SESSION_STATUS_CHANGED");
        assertThat(again).extracting(j -> j.get("type").asString()).containsExactly("LEAVE", "SESSION_STATUS_CHANGED");
        assertThat(again).extracting(j -> j.get("seq").asLong())
                .containsExactlyElementsOf(first.stream().map(j -> j.get("seq").asLong()).toList());
    }

    @Test
    @DisplayName("resume: 많으면 SYSTEM_RESYNC(건수·크기 한도), 적으면 이벤트 뒤 RESUMED, 끝에서는 빈 RESUMED 후 라이브")
    void resume() {
        String owner = api.token("b2b-sales.customer");
        String agent = api.token("b2b-sales.user");
        String sid = api.post("/sessions", Map.of("title", "resume 300건"), owner).text("id");
        // 참여 전에 연결해 두면 접속 기준이 ONLINE이라, 아래 resume 연결이 RECONNECT 이벤트를 만들지 않는다.
        user(agent, null);
        api.post("/sessions/" + sid + "/join", null, agent);
        for (int i = 0; i < 300; i++) {
            api.post("/sessions/" + sid + "/events", message("%03d ".formatted(i) + "가".repeat(1990), UUID.randomUUID().toString()), owner);
        }
        assertThat(api.get("/sessions/" + sid + "/events", owner, Map.of("afterSeq", 300)).body().get("nextAfterSeq").asLong())
                .isEqualTo(304);

        StompClient x = user(agent, sid, Map.of("resume-after-seq", "0"));
        Frame resync = x.expect(onSubscription("s1"), WAIT);
        assertThat(resync.json().get("kind").asString()).isEqualTo("SYSTEM_RESYNC");
        assertThat(resync.json().get("fromSeq").asLong()).isEqualTo(1);
        assertThat(x.isClosed()).isFalse();

        List<Long> seqs = new ArrayList<>();
        long after = 0;
        boolean more = true;
        while (more) {
            Response p = api.get("/sessions/" + sid + "/events", agent, Map.of("afterSeq", after, "limit", 200));
            p.body().get("items").forEach(i -> seqs.add(i.get("seq").asLong()));
            after = p.body().get("nextAfterSeq").asLong();
            more = p.body().get("hasMore").asBoolean();
        }
        assertThat(seqs).as("RESYNC 뒤 보충 조회는 빈틈이 없다")
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 304).boxed().toList());

        x.subscribe(topic(sid), "s2", Map.of("resume-after-seq", "204"));
        assertThat(x.expect(onSubscription("s2"), WAIT).json().get("kind").asString())
                .as("100건(약 600KB)은 크기 한도로 RESYNC").isEqualTo("SYSTEM_RESYNC");

        x.subscribe(topic(sid), "s3", Map.of("resume-after-seq", "274"));
        List<JsonNode> got = new ArrayList<>();
        Frame f;
        while ((f = x.expect(onSubscription("s3"), WAIT)) != null) {
            got.add(f.json());
            if ("RESUMED".equals(f.json().path("kind").asString(null))) {
                break;
            }
        }
        assertThat(got.stream().filter(j -> j.has("seq")).map(j -> j.get("seq").asLong()))
                .containsExactlyElementsOf(LongStream.rangeClosed(275, 304).boxed().toList());
        JsonNode resumed = got.getLast();
        assertThat(resumed.get("kind").asString()).isEqualTo("RESUMED");
        assertThat(resumed.get("fromSeq").asLong()).isEqualTo(275);
        assertThat(resumed.get("toSeq").asLong()).isEqualTo(304);

        x.subscribe(topic(sid), "s4", Map.of("resume-after-seq", "304"));
        JsonNode head = x.expect(onSubscription("s4"), WAIT).json();
        assertThat(head.get("kind").asString()).isEqualTo("RESUMED");
        assertThat(head.get("fromSeq").asLong()).isEqualTo(305);
        assertThat(head.get("toSeq").asLong()).isEqualTo(304);

        String live = UUID.randomUUID().toString();
        api.post("/sessions/" + sid + "/events", message("라이브", live), owner);
        Frame liveFrame = x.expect(onSubscription("s4", j -> live.equals(j.path("clientEventId").asString(null))), WAIT);
        assertThat(liveFrame.json().get("seq").asLong()).isEqualTo(305);
        assertThat(x.isClosed()).isFalse();
    }
}
