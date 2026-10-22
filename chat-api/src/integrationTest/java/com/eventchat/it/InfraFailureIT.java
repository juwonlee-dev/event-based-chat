package com.eventchat.it;

import static com.eventchat.testsupport.StompClient.onSubscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventchat.it.support.ChatContainers;
import com.eventchat.it.support.IntegrationTest;
import com.eventchat.it.support.OwnerDb;
import com.eventchat.testsupport.Api.Response;
import com.eventchat.testsupport.StompClient;
import com.eventchat.testsupport.StompClient.Frame;
import com.github.dockerjava.api.DockerClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * 공유 컨테이너를 재시작·일시 정지하므로 다른 통합 테스트가 모두 끝난 뒤에 돈다
 * (src/integrationTest/resources/junit-platform.properties의 클래스 순서).
 * Redis는 호스트 포트를 고정해 두어서 재시작해도 앱이 같은 주소로 다시 붙는다.
 * Kafka는 재시작하면 광고 주소의 포트가 바뀌므로 정지 대신 일시 정지로 브로커 장애를 만든다.
 */
@DisplayName("인프라 장애")
@Order(Integer.MAX_VALUE)
class InfraFailureIT extends IntegrationTest {

    private static final DockerClient DOCKER = DockerClientFactory.instance().client();

    private final List<StompClient> clients = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        clients.forEach(StompClient::close);
        for (String id : List.of(ChatContainers.REDIS.getContainerId(), ChatContainers.KAFKA.getContainerId())) {
            if (Boolean.TRUE.equals(DOCKER.inspectContainerCmd(id).exec().getState().getPaused())) {
                DOCKER.unpauseContainerCmd(id).exec();
            }
        }
    }

    private StompClient connect(String token, String sid) {
        StompClient client = StompClient.connect(wsUrl(), token);
        assertThat(client.isConnected()).isTrue();
        clients.add(client);
        client.subscribe("/user/queue/system", "system");
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

    private String session(String owner, String other, String title) {
        String sid = api.post("/sessions", Map.of("title", title), owner).text("id");
        api.post("/sessions/" + sid + "/join", null, other);
        return sid;
    }

    private Response message(String sid, String token, String content, String clientEventId) {
        return api.post("/sessions/" + sid + "/events",
                Map.of("type", "MESSAGE", "clientEventId", clientEventId, "payload", Map.of("content", content)), token);
    }

    private static Frame awaitMessage(StompClient client, String clientEventId) {
        return client.expect(onSubscription("s1", j -> clientEventId.equals(j.path("clientEventId").asString(null))),
                Duration.ofSeconds(5));
    }

    private double counter(String name) {
        Response metric = management.get("/actuator/metrics/" + name, null);
        return metric.status() == 200 ? metric.body().get("measurements").get(0).get("value").asDouble() : 0;
    }

    private List<String> presenceEvents(String sid, String token) {
        return api.get("/sessions/" + sid + "/events", token, Map.of("limit", 200)).body().get("items").valueStream()
                .map(e -> e.get("type").asString())
                .filter(t -> t.equals("DISCONNECT") || t.equals("RECONNECT"))
                .toList();
    }

    @Test
    @DisplayName("Redis 재시작: Pub/Sub을 다시 구독하고 세션 토픽에 SYSTEM_RESYNC를 보낸다")
    void redisRestart() {
        String a = api.token("car-sales.user");
        String sid = session(a, api.token("car-sales.customer"), "Redis 재시작");
        StompClient client = connect(a, sid);

        DOCKER.restartContainerCmd(ChatContainers.REDIS.getContainerId()).exec();
        Frame resync = client.expect(onSubscription("s1", j -> "SYSTEM_RESYNC".equals(j.path("kind").asString(null))),
                Duration.ofSeconds(30));
        assertThat(resync).isNotNull();
        assertThat(resync.json().get("sessionId").asString()).isEqualTo(sid);
    }

    @Test
    @DisplayName("Redis 응답 없음: SYSTEM_DEGRADED를 알리고 쓰기와 로컬 전달은 계속되며, 돌아오면 SYSTEM_RECOVERED")
    void redisPause() {
        String a = api.token("car-sales.user");
        String sid = session(a, api.token("car-sales.customer"), "Redis 일시 정지");
        StompClient client = connect(a, sid);

        DOCKER.pauseContainerCmd(ChatContainers.REDIS.getContainerId()).exec();
        Frame degraded = client.expect(onSubscription("system",
                j -> "SYSTEM_DEGRADED".equals(j.path("kind").asString(null))), Duration.ofSeconds(15));
        assertThat(degraded).isNotNull();
        assertThat(degraded.json().get("pollIntervalMs").asInt()).isEqualTo(5000);

        String during = UUID.randomUUID().toString();
        assertThat(message(sid, a, "저하 중 메시지", during).status()).isEqualTo(201);
        assertThat(awaitMessage(client, during)).as("같은 노드 구독자에게는 전달된다").isNotNull();

        DOCKER.unpauseContainerCmd(ChatContainers.REDIS.getContainerId()).exec();
        assertThat(client.expect(onSubscription("system", j -> "SYSTEM_RECOVERED".equals(j.path("kind").asString(null))),
                Duration.ofSeconds(20))).isNotNull();
        String after = UUID.randomUUID().toString();
        client.send(app(sid), Map.of("type", "MESSAGE", "clientEventId", after, "payload", Map.of("content", "복구 후")));
        assertThat(awaitMessage(client, after)).isNotNull();
    }

    @Test
    @DisplayName("Redis 재시작 뒤 접속 정보를 다시 등록하고, 상태가 그대로면 presence 기록이 생기지 않는다")
    void presenceSurvivesRedisRestart() {
        String a = api.token("bank.user");
        String b = api.token("bank.customer");
        StompClient watcher = connect(a, null);
        StompClient cb = connect(b, null);
        String sid = session(a, b, "Redis 재시작과 presence");
        watcher.subscribe(topic(sid), "s1");
        sleep(500);
        List<String> before = presenceEvents(sid, a);
        double reregistered = counter("chat.presence.reregistered");

        DOCKER.restartContainerCmd(ChatContainers.REDIS.getContainerId()).exec();
        await().atMost(Duration.ofSeconds(20)).until(() -> counter("chat.presence.reregistered") > reregistered);
        sleep(12_000); // 재시작 직후 대조 보류(lease 2배 = 6초)와 다음 대조 주기(5초)가 지나도록
        assertThat(presenceEvents(sid, a)).isEqualTo(before);

        cb.close();
        String idb = api.userId("bank.customer").toString();
        Frame disconnect = watcher.expect(onSubscription("s1", j -> "DISCONNECT".equals(j.path("type").asString(null))
                && idb.equals(j.path("subjectId").asString(null))), Duration.ofSeconds(15));
        assertThat(disconnect).as("재시작 뒤에도 끊김은 정상 기록된다").isNotNull();
        assertThat(disconnect.json().get("payload").get("reason").asString()).isEqualTo("SOCKET_CLOSED");
    }

    @Test
    @DisplayName("Kafka가 멈춰도 쓰기는 성공하고 outbox에 쌓였다가, 돌아오면 발행되어 스냅샷이 만들어진다")
    void kafkaOutage() {
        String a = api.token("community-center.user");
        String sid = session(a, api.token("community-center.customer"), "Kafka 중단");

        DOCKER.pauseContainerCmd(ChatContainers.KAFKA.getContainerId()).exec();
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            statuses.add(message(sid, a, "m" + i, UUID.randomUUID().toString()).status());
        }
        assertThat(statuses).containsOnly(201);
        sleep(3_000);
        assertThat(OwnerDb.scalar("SELECT count(*) FROM event_outbox WHERE key = '" + sid + "'")).isEqualTo("1");

        DOCKER.unpauseContainerCmd(ChatContainers.KAFKA.getContainerId()).exec();
        long last = api.get("/sessions/" + sid, a).body().get("lastSeq").asLong();
        await().atMost(Duration.ofSeconds(90)).until(() ->
                String.valueOf(last).equals(OwnerDb.scalar("SELECT last_seq FROM session_snapshot WHERE session_id = '" + sid + "'")));
        await().atMost(Duration.ofSeconds(10)).until(() -> OwnerDb.scalar("SELECT count(*) FROM event_outbox").equals("0"));
    }
}
