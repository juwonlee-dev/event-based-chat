package com.eventchat.it;

import static com.eventchat.testsupport.StompClient.onSubscription;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventchat.it.support.IntegrationTest;
import com.eventchat.it.support.OwnerDb;
import com.eventchat.testsupport.Api.Response;
import com.eventchat.testsupport.StompClient;
import com.eventchat.testsupport.StompClient.Frame;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 시간은 {@link IntegrationTest}에서 줄인 값 기준이다: 유예 3초, 스위퍼 0.5초, 클라이언트 heart-beat 1초
 * (3초 동안 프레임이 없으면 서버가 끊는다), 대조 5초. 운영 기본값(유예 15초, heart-beat 5초)에서는
 * 소켓이 닫히면 약 17초, 프로세스가 멈추면 약 30~40초 뒤 표시된다.
 */
@DisplayName("접속 상태(presence)")
class PresenceIT extends IntegrationTest {

    private final List<StompClient> clients = new ArrayList<>();

    /** presence는 사용자 단위라 남은 연결의 끊김이 다음 테스트 세션에 기록되지 않게 유예 시간만큼 기다린다. */
    @AfterEach
    void closeClients() throws InterruptedException {
        clients.forEach(StompClient::close);
        Thread.sleep(4_000);
    }

    private StompClient connect(String token, String sid) {
        StompClient client = StompClient.connect(wsUrl(), token);
        assertThat(client.isConnected()).isTrue();
        clients.add(client);
        if (sid != null) {
            client.subscribe(topic(sid), "s1");
        }
        sleep(300);
        return client;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<JsonNode> presenceEvents(String sid, String token) {
        return api.get("/sessions/" + sid + "/events", token, Map.of("limit", 200)).body().get("items").valueStream()
                .filter(e -> List.of("DISCONNECT", "RECONNECT").contains(e.get("type").asString()))
                .toList();
    }

    private record Timed(JsonNode event, double seconds) {
    }

    private static Timed waitEvent(StompClient watcher, String type, String subject, Duration timeout) {
        long start = System.nanoTime();
        Frame frame = watcher.expect(onSubscription("s1", j -> type.equals(j.path("type").asString(null))
                && subject.equals(j.path("subjectId").asString(null))), timeout);
        return new Timed(frame == null ? null : frame.json(), (System.nanoTime() - start) / 1e9);
    }

    /** 두 사람이 먼저 접속한 뒤 만든 IN_PROGRESS 세션. 감시 연결(a)은 세션 토픽을 구독한다. */
    private record Fixture(String sid, String a, String b, String idb, StompClient watcher, Response joined) {
    }

    private Fixture fixture(String userA, String userB, String title) {
        String a = api.token(userA);
        String b = api.token(userB);
        StompClient watcher = connect(a, null);
        connect(b, null);
        String sid = api.post("/sessions", Map.of("title", title), a).text("id");
        Response joined = api.post("/sessions/" + sid + "/join", null, b);
        watcher.subscribe(topic(sid), "s1");
        sleep(300);
        return new Fixture(sid, a, b, api.userId(userB).toString(), watcher, joined);
    }

    @Test
    @DisplayName("참여 응답과 상태 전이 이벤트에 두 사람의 presence 기준점(state, ver)이 담긴다")
    void baseline() {
        Fixture f = fixture("insurance.user", "insurance.customer", "presence 기준점");
        assertThat(f.joined().status()).isEqualTo(200);
        assertThat(f.joined().body().get("participants").valueStream().map(p -> p.get("presence").asString()))
                .containsOnly("ONLINE");

        JsonNode changed = api.get("/sessions/" + f.sid() + "/events", f.a(), Map.of("limit", 200)).body().get("items")
                .valueStream().filter(e -> e.get("type").asString().equals("SESSION_STATUS_CHANGED")).findFirst().orElseThrow();
        JsonNode baseline = changed.get("payload").get("presenceBaseline");
        assertThat(baseline.get(api.userId("insurance.user").toString()).get("state").asString()).isEqualTo("ONLINE");
        assertThat(baseline.get(f.idb()).get("state").asString()).isEqualTo("ONLINE");
        assertThat(baseline.get(f.idb()).get("ver").asLong()).isPositive();
    }

    @Test
    @DisplayName("유예 시간 안에 다시 붙으면 DISCONNECT/RECONNECT를 남기지 않는다")
    void reconnectWithinGrace() {
        Fixture f = fixture("insurance.user", "insurance.customer", "유예 안 재연결");
        clients.getLast().close();
        sleep(1_000);
        connect(f.b(), f.sid());
        sleep(5_000);
        assertThat(presenceEvents(f.sid(), f.a())).isEmpty();
    }

    @Test
    @DisplayName("heart-beat가 멈추면 HEARTBEAT_TIMEOUT, 다시 붙으면 RECONNECT, 소켓을 닫으면 SOCKET_CLOSED")
    void disconnectReconnectSequence() {
        Fixture f = fixture("insurance.user", "insurance.customer", "끊김과 재연결");
        StompClient b = clients.getLast();
        api.post("/sessions/" + f.sid() + "/events", Map.of("type", "TYPING_STARTED"), f.b());

        b.pauseHeartbeat();
        Timed hb = waitEvent(f.watcher(), "DISCONNECT", f.idb(), Duration.ofSeconds(20));
        assertThat(hb.event()).isNotNull();
        assertThat(hb.event().get("payload").get("reason").asString()).isEqualTo("HEARTBEAT_TIMEOUT");
        assertThat(hb.event().has("actorId")).as("서버가 남긴 기록이라 actor가 없다").isFalse();
        assertThat(hb.event().get("payload").has("lastSeenAt")).isTrue();
        assertThat(hb.seconds()).as("heart-beat 3초 + 유예 3초 안팎").isBetween(4.0, 12.0);
        assertThat(b.awaitClosed(Duration.ofSeconds(1))).as("서버가 멈춘 연결을 닫는다").isTrue();
        assertThat(api.post("/sessions/" + f.sid() + "/events", Map.of("type", "TYPING_STOPPED"), f.b()).status())
                .as("DISCONNECT가 입력 중을 끈다").isEqualTo(204);

        StompClient back = connect(f.b(), f.sid());
        Timed reconnect = waitEvent(f.watcher(), "RECONNECT", f.idb(), Duration.ofSeconds(10));
        assertThat(reconnect.event().get("payload").has("offlineSince")).isTrue();
        assertThat(reconnect.event().get("payload").get("offlineMs").asLong()).isPositive();

        back.close();
        Timed closed = waitEvent(f.watcher(), "DISCONNECT", f.idb(), Duration.ofSeconds(15));
        assertThat(closed.event().get("payload").get("reason").asString()).isEqualTo("SOCKET_CLOSED");
        assertThat(closed.seconds()).as("유예 3초 + 스위퍼 0.5초 안팎").isBetween(2.0, 7.0);

        assertThat(presenceEvents(f.sid(), f.a())).extracting(e -> e.get("type").asString())
                .containsExactly("DISCONNECT", "RECONNECT", "DISCONNECT");
        assertThat(api.get("/sessions/" + f.sid(), f.a()).body().get("participants").valueStream()
                .filter(p -> p.get("userId").asString().equals(f.idb())).map(p -> p.get("presence").asString()))
                .containsExactly("OFFLINE");
    }

    @Test
    @DisplayName("연결 두 개 중 하나만 닫으면 기록하지 않는다")
    void twoTabs() {
        Fixture f = fixture("insurance.user", "insurance.customer", "탭 두 개");
        StompClient second = connect(f.b(), f.sid());
        clients.get(1).close();
        sleep(5_000);
        assertThat(presenceEvents(f.sid(), f.a())).isEmpty();
        assertThat(second.isClosed()).isFalse();
    }

    @Test
    @DisplayName("WAITING 세션과 종료된 세션에는 presence를 기록하지 않는다")
    void notRecordedOutsideInProgress() {
        String a = api.token("insurance.user");
        String b = api.token("insurance.customer");
        connect(a, null);
        StompClient cb = connect(b, null);
        String waiting = api.post("/sessions", Map.of("title", "대기 세션"), b).text("id");
        String ended = api.post("/sessions", Map.of("title", "종료 세션"), a).text("id");
        api.post("/sessions/" + ended + "/join", null, b);
        api.post("/sessions/" + ended + "/end", null, a);

        cb.close();
        sleep(5_000);
        assertThat(presenceEvents(waiting, b)).isEmpty();
        assertThat(presenceEvents(ended, a)).isEmpty();
    }

    @Test
    @DisplayName("DB보다 오래된 버전의 신호는 버리고, 대조 작업이 더 큰 버전으로 OFFLINE을 맞춘다")
    void staleSignalIsDroppedAndReconciled() {
        Fixture f = fixture("community-center.user", "community-center.customer", "늦은 신호");
        // DB가 이미 더 새로운 버전(1시간 뒤)을 반영했다고 만든다 → 이후 Redis 전이는 늦게 온 신호다.
        long future = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now().plus(Duration.ofHours(1)));
        OwnerDb.update("UPDATE session_participant SET presence_ver = " + future
                + " WHERE session_id = '" + f.sid() + "' AND user_id = '" + f.idb() + "'");

        clients.getLast().close();
        Timed first = waitEvent(f.watcher(), "DISCONNECT", f.idb(), Duration.ofSeconds(30));
        assertThat(first.event()).isNotNull();
        assertThat(first.event().get("payload").get("reason").asString()).isEqualTo("RECONCILED");
        assertThat(presenceEvents(f.sid(), f.a())).extracting(e -> e.get("payload").get("reason").asString())
                .containsExactly("RECONCILED");
        assertThat(OwnerDb.scalar("SELECT presence || ',' || (presence_ver > " + future + ") FROM session_participant"
                + " WHERE session_id = '" + f.sid() + "' AND user_id = '" + f.idb() + "'")).isEqualTo("OFFLINE,true");
    }
}
