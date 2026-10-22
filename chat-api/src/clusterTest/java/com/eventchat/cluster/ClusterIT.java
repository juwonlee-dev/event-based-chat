package com.eventchat.cluster;

import static com.eventchat.testsupport.StompClient.onSubscription;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventchat.testsupport.Api;
import com.eventchat.testsupport.StompClient;
import com.eventchat.testsupport.StompClient.Frame;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * nginx 뒤 앱 여러 대(compose.cluster.yaml)를 대상으로 한다. 시간은 운영 기본값이다(유예 15초, lease 15초).
 * 장애 주입(fault 태그)은 앱 컨테이너를 강제 종료·일시 정지하므로 scripts/test/cluster.sh에서 고를 때만 돈다.
 */
@DisplayName("클러스터")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClusterIT {

    static final String BASE_URL = System.getProperty("cluster.baseUrl", "http://127.0.0.1:8000");
    static final String WS_URL = BASE_URL.replaceFirst("^http", "ws") + "/ws";
    static final String PROJECT = System.getProperty("cluster.project", "event-based-chat");

    private final Api api = new Api(BASE_URL);
    private final List<String> restore = new ArrayList<>();
    private Map<String, String> nodes;
    private String tokenA;
    private String tokenB;
    private String ida;
    private String idb;
    private StompClient a;
    private StompClient b;
    private String sid;

    @BeforeAll
    void session() {
        nodes = Docker.appContainers(PROJECT);
        assertThat(nodes).as("앱 인스턴스가 2대 이상이어야 한다").hasSizeGreaterThanOrEqualTo(2);
        tokenA = api.token("insurance.user");
        tokenB = api.token("insurance.customer");
        ida = api.userId("insurance.user").toString();
        idb = api.userId("insurance.customer").toString();
        a = connect(tokenA, null, null);
        b = connect(tokenB, ip(a), null);
        sid = api.post("/sessions", Map.of("title", "클러스터 확인"), tokenA).text("id");
        api.post("/sessions/" + sid + "/join", null, tokenB);
        a.subscribe("/topic/sessions/" + sid, "s1");
        b.subscribe("/topic/sessions/" + sid, "s1");
        sleep(500);
    }

    @AfterAll
    void cleanUp() {
        restore.forEach(command -> {
            try {
                Docker.run(command.split(" "));
            } catch (IllegalStateException ignored) {
                // 이미 되돌려진 상태
            }
        });
        a.close();
        b.close();
    }

    static String ip(StompClient client) {
        return client.upstream() == null ? null : client.upstream().split(":")[0];
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** nginx(least_conn)를 거쳐 연결하되 원하는 인스턴스에 붙을 때까지 다시 연결한다. 붙잡아 둔 연결은 마지막에 닫는다. */
    static StompClient connect(String token, String avoid, String want) {
        List<StompClient> held = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                StompClient client = StompClient.connect(WS_URL, token);
                if (!client.isConnected()) {
                    client.close();
                    sleep(500);
                    continue;
                }
                if ((want == null || want.equals(ip(client))) && (avoid == null || !avoid.equals(ip(client)))) {
                    return client;
                }
                held.add(client);
            }
            throw new IllegalStateException("원하는 인스턴스에 붙지 못함 want=" + want + " avoid=" + avoid);
        } finally {
            held.forEach(StompClient::close);
        }
    }

    private void send(StompClient client, String content) {
        client.send("/app/sessions/" + sid + "/events",
                Map.of("type", "MESSAGE", "clientEventId", UUID.randomUUID(), "payload", Map.of("content", content)));
    }

    private static Frame awaitMessage(StompClient client, String sub, String content, Duration timeout) {
        return client.expect(onSubscription(sub, j -> content.equals(j.path("payload").path("content").asString(null))),
                timeout);
    }

    private List<JsonNode> events(String session, String token) {
        return api.get("/sessions/" + session + "/events", token, Map.of("afterSeq", 0, "limit", 200))
                .body().get("items").valueStream().toList();
    }

    private List<JsonNode> presenceEvents(String subject) {
        return events(sid, tokenA).stream()
                .filter(e -> List.of("DISCONNECT", "RECONNECT").contains(e.get("type").asString()))
                .filter(e -> subject.equals(e.path("subjectId").asString(null)))
                .toList();
    }

    @Test
    @Order(1)
    @DisplayName("서로 다른 인스턴스에 붙은 두 사람이 양방향으로 실시간 메시지를 주고받는다")
    void crossNode() {
        assertThat(ip(a)).isNotEqualTo(ip(b));
        send(a, "다른 노드로");
        assertThat(awaitMessage(b, "s1", "다른 노드로", Duration.ofSeconds(5))).isNotNull();
        send(b, "답장");
        assertThat(awaitMessage(a, "s1", "답장", Duration.ofSeconds(5))).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("번갈아 100건을 빠르게 보내도 양쪽 모두 빠짐없이 받고 RESYNC가 없다(도착 순서는 seq로 재정렬)")
    void interleavedBurst() {
        a.drain(Duration.ofMillis(500));
        b.drain(Duration.ofMillis(100));
        long start = events(sid, tokenA).getLast().get("seq").asLong();
        int n = 100;
        for (int i = 0; i < n; i++) {
            send(i % 2 == 0 ? a : b, "번갈아 " + i);
        }
        Set<Long> expected = new HashSet<>(LongStream.rangeClosed(start + 1, start + n).boxed().toList());
        for (StompClient client : List.of(a, b)) {
            Set<Long> got = new HashSet<>();
            int resync = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (!got.containsAll(expected) && System.nanoTime() < deadline) {
                Frame frame = client.expect(onSubscription("s1"), Duration.ofSeconds(2));
                if (frame == null) {
                    break;
                }
                JsonNode body = frame.json();
                if ("SYSTEM_RESYNC".equals(body.path("kind").asString(null))) {
                    resync++;
                } else if (body.has("seq")) {
                    got.add(body.get("seq").asLong());
                }
            }
            assertThat(got).containsAll(expected);
            assertThat(resync).isZero();
        }
    }

    @Test
    @Order(3)
    @Tag("fault")
    @DisplayName("인스턴스를 강제 종료하면 다시 붙은 사용자는 resume으로 이어 받고, 붙지 않은 사용자는 약 30초 뒤 NODE_LOST")
    void nodeKilled() {
        String tokenC = api.token("bank.user");
        String tokenD = api.token("bank.customer");
        String idd = api.userId("bank.customer").toString();
        String dead = ip(b);
        StompClient d = connect(tokenD, null, dead);
        StompClient c = connect(tokenC, dead, null);
        String sid2 = api.post("/sessions", Map.of("title", "노드 장애"), tokenC).text("id");
        api.post("/sessions/" + sid2 + "/join", null, tokenD);
        c.subscribe("/topic/sessions/" + sid2, "s2");
        sleep(500);
        long last = events(sid, tokenA).getLast().get("seq").asLong();

        String victim = nodes.get(dead);
        long killedAt = System.nanoTime();
        Docker.run("kill", victim);
        restore.add("start " + victim);
        assertThat(b.awaitClosed(Duration.ofSeconds(10))).isTrue();
        assertThat(d.awaitClosed(Duration.ofSeconds(10))).isTrue();

        b = connect(tokenB, dead, null);
        b.subscribe("/topic/sessions/" + sid, "s1", Map.of("resume-after-seq", String.valueOf(last)));
        Frame resumed = b.expect(onSubscription("s1", j -> "RESUMED".equals(j.path("kind").asString(null))
                || "SYSTEM_RESYNC".equals(j.path("kind").asString(null))), Duration.ofSeconds(10));
        assertThat(ip(b)).isNotEqualTo(dead);
        assertThat(resumed.json().get("kind").asString()).isEqualTo("RESUMED");
        send(a, "재연결 뒤");
        assertThat(awaitMessage(b, "s1", "재연결 뒤", Duration.ofSeconds(5))).isNotNull();

        Frame lost = c.expect(onSubscription("s2", j -> "DISCONNECT".equals(j.path("type").asString(null))
                && idd.equals(j.path("subjectId").asString(null))), Duration.ofSeconds(60));
        double took = (System.nanoTime() - killedAt) / 1e9;
        assertThat(lost).isNotNull();
        assertThat(lost.json().get("payload").get("reason").asString()).isEqualTo("NODE_LOST");
        assertThat(took).as("lease 15초 + 유예 15초 안팎").isBetween(25.0, 45.0);
        assertThat(presenceEvents(idb)).extracting(e -> e.get("type").asString()).doesNotContain("DISCONNECT");
        c.close();

        Docker.run("start", victim);
        restore.remove("start " + victim);
        nodes = Docker.appContainers(PROJECT);
    }

    @Test
    @Order(4)
    @Tag("fault")
    @DisplayName("인스턴스를 20초 멈췄다 재개하면 lease 상실을 알아채 다시 등록하고, 유예 안에 바로잡혀 DISCONNECT가 남지 않는다")
    void nodePaused() {
        String victim = nodes.get(ip(a));
        int before = presenceEvents(ida).size();
        Docker.run("pause", victim);
        restore.add("unpause " + victim);
        sleep(20_000);
        Docker.run("unpause", victim);
        restore.remove("unpause " + victim);
        sleep(8_000);

        assertThat(Docker.run("logs", "--since", "40s", victim)).contains("trigger=LEASE_LOST");
        if (a.isClosed()) {
            a = connect(tokenA, null, null); // 정지 중 heart-beat가 끊겨 닫혔으면 바로 다시 붙는다
        }
        sleep(25_000);
        List<JsonNode> after = presenceEvents(ida);
        assertThat(after.subList(before, after.size())).extracting(e -> e.get("type").asString())
                .doesNotContain("DISCONNECT");
    }

    @Test
    @Order(5)
    @Tag("fault")
    @DisplayName("인스턴스를 정상 종료하면 연결을 1012(서비스 재시작)로 닫아 클라이언트가 다시 붙게 한다")
    void gracefulShutdownDrains() throws Exception {
        String victim = nodes.get(ip(a));
        Thread stopping = Thread.ofVirtual().start(() -> Docker.run("stop", "-t", "45", victim));
        restore.add("start " + victim);
        try {
            assertThat(a.awaitClosed(Duration.ofSeconds(20))).isTrue();
            assertThat(a.closeCode()).isEqualTo(1012);
        } finally {
            stopping.join(Duration.ofSeconds(60));
            Docker.run("start", victim);
            restore.remove("start " + victim);
        }
    }
}
