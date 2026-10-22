package com.eventchat.it;

import static com.eventchat.testsupport.StompClient.onSubscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventchat.it.support.ChatContainers;
import com.eventchat.it.support.IntegrationTest;
import com.eventchat.it.support.OwnerDb;
import com.eventchat.testsupport.Api;
import com.eventchat.testsupport.Api.Response;
import com.eventchat.testsupport.StompClient;
import com.eventchat.testsupport.StompClient.Frame;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * 메시지 120건(이벤트 122건) 세션 하나를 여러 검증이 차례로 쓴다. 자동 스냅샷 → 두 경로 비교 → 수동 스냅샷 →
 * 현재 상태 → 퇴장(종단) 순서가 의미 있으므로 순서를 고정한다.
 */
@DisplayName("복원(timeline)과 스냅샷")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RestoreIT extends IntegrationTest {

    private Api http;
    private String a;
    private String b;
    private String c;
    private String admin;
    private String ida;
    private String idb;
    private StompClient watcher;
    private String sid;
    private long autoSnapshot;

    @BeforeAll
    void conversation() {
        http = new Api("http://localhost:" + port);
        a = http.token("telecom.user");
        b = http.token("telecom.customer");
        c = http.token("furniture.user");
        admin = http.token("admin");
        ida = http.userId("telecom.user").toString();
        idb = http.userId("telecom.customer").toString();
        watcher = StompClient.connect(wsUrl(), a);
        sid = http.post("/sessions", Map.of("title", "복원 확인"), a).text("id");
        http.post("/sessions/" + sid + "/join", null, b);
        watcher.subscribe(topic(sid), "s1");
        for (int i = 0; i < 60; i++) {
            message(a, "상담사 " + i);
            message(b, "고객 " + i);
        }
    }

    @AfterAll
    void close() {
        watcher.close();
    }

    private void message(String token, String content) {
        Response response = http.post("/sessions/" + sid + "/events",
                Map.of("type", "MESSAGE", "clientEventId", UUID.randomUUID(), "payload", Map.of("content", content)), token);
        assertThat(response.status()).isEqualTo(201);
    }

    private Response timeline(String session, String token, Map<String, ?> params) {
        return api.get("/sessions/" + session + "/timeline", token, params);
    }

    private JsonNode timeline(String token, Map<String, ?> params) {
        Response response = timeline(sid, token, params);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return response.body();
    }

    private long lastSeq() {
        return api.get("/sessions/" + sid, a).body().get("lastSeq").asLong();
    }

    private static Long snapshotSeq(String session) {
        String value = OwnerDb.scalar("SELECT last_seq FROM session_snapshot WHERE session_id = '" + session + "'");
        return value == null ? null : Long.parseLong(value);
    }

    private static JsonNode participant(JsonNode view, String userId) {
        return view.get("participants").valueStream()
                .filter(p -> p.get("userId").asString().equals(userId)).findFirst().orElseThrow();
    }

    /** 쓰기 측 현재 상태(세션 상세)와 이벤트를 fold한 현재 상태(timeline)가 같아야 한다. */
    private void assertWriteSideMatchesTimeline() {
        JsonNode detail = api.get("/sessions/" + sid, a).body();
        JsonNode view = timeline(a, Map.of());
        Map<String, String> writeSide = new HashMap<>();
        detail.get("participants").forEach(p -> writeSide.put(p.get("userId").asString(),
                p.get("state").asString() + "/" + p.get("presence").asString()));
        Map<String, String> folded = new HashMap<>();
        view.get("participants").forEach(p -> folded.put(p.get("userId").asString(),
                p.get("state").asString() + "/" + p.get("presence").get("status").asString()));
        assertThat(folded).isEqualTo(writeSide);
        assertThat(view.get("asOf").get("status").asString()).isEqualTo(detail.get("status").asString());
        assertThat(view.get("asOf").get("seq").asLong()).isEqualTo(detail.get("lastSeq").asLong());
    }

    @Test
    @Order(1)
    @DisplayName("100건 경계를 넘으면 outbox → Kafka → 작업자가 스냅샷을 만들고 outbox를 비운다")
    void automaticSnapshot() {
        await().atMost(Duration.ofSeconds(30)).until(() -> snapshotSeq(sid) != null && snapshotSeq(sid) >= 100);
        autoSnapshot = snapshotSeq(sid);
        await().atMost(Duration.ofSeconds(10)).until(() -> OwnerDb.scalar("SELECT count(*) FROM event_outbox").equals("0"));
    }

    @Test
    @Order(2)
    @DisplayName("같은 시점이면 스냅샷+증분 복원과 전체 리플레이의 stateHash가 같다")
    void snapshotAndFullReplayAgree() {
        long last = lastSeq();
        for (long s : List.of(last - 3, 40L)) {
            JsonNode inc = timeline(a, Map.of("atSeq", s)).get("restoredFrom");
            JsonNode full = timeline(admin, Map.of("atSeq", s, "ignoreSnapshot", true)).get("restoredFrom");
            String expected = s >= autoSnapshot ? "SNAPSHOT_INCREMENTAL" : "FULL_REPLAY";
            assertThat(inc.get("mode").asString()).as("S=" + s).isEqualTo(expected);
            assertThat(full.get("mode").asString()).isEqualTo("FULL_REPLAY");
            assertThat(full.get("stateHash")).as("S=" + s).isEqualTo(inc.get("stateHash"));
            assertThat(full.get("replayedEvents").asLong()).isEqualTo(s);
            if (expected.equals("SNAPSHOT_INCREMENTAL")) {
                assertThat(inc.get("snapshotSeq").asLong()).isEqualTo(autoSnapshot);
                assertThat(inc.get("replayedEvents").asLong()).isEqualTo(s - autoSnapshot);
            }
        }
        Response forbidden = timeline(sid, a, Map.of("atSeq", 10, "ignoreSnapshot", true));
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.code()).isEqualTo("ADMIN_ONLY");
    }

    @Test
    @Order(3)
    @DisplayName("스냅샷 API는 추가분만 적용하고(UPDATED), 바뀐 게 없으면 UNCHANGED, 관리자만 쓸 수 있다")
    void snapshotApi() {
        long last = lastSeq();
        Response first = api.post("/sessions/" + sid + "/snapshots", null, admin);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.text("mode")).isEqualTo("UPDATED");
        assertThat(first.body().get("previousSeq").asLong()).isEqualTo(autoSnapshot);
        assertThat(first.body().get("appliedEvents").asLong()).isEqualTo(last - autoSnapshot);
        assertThat(first.body().get("lastSeq").asLong()).isEqualTo(last);

        Response again = api.post("/sessions/" + sid + "/snapshots", null, admin);
        assertThat(again.text("mode")).isEqualTo("UNCHANGED");
        assertThat(again.body().get("appliedEvents").asLong()).isZero();
        assertThat(again.text("stateHash")).isEqualTo(first.text("stateHash"));

        JsonNode current = timeline(a, Map.of("atSeq", last)).get("restoredFrom");
        assertThat(current.get("stateHash").asString()).isEqualTo(first.text("stateHash"));
        assertThat(current.get("replayedEvents").asLong()).isZero();
        assertThat(api.post("/sessions/" + sid + "/snapshots", null, a).code()).isEqualTo("ADMIN_ONLY");
    }

    @Test
    @Order(4)
    @DisplayName("실시간으로 받은 typing과 timeline typing이 같고, TTL이 지나거나 메시지를 보내면 꺼진다")
    void typing() throws InterruptedException {
        watcher.drain(Duration.ofMillis(300));
        api.post("/sessions/" + sid + "/events", Map.of("type", "TYPING_STARTED"), b);
        Frame frame = watcher.expect(onSubscription("s1", j -> "TYPING_STARTED".equals(j.path("type").asString(null))),
                Duration.ofSeconds(5));
        JsonNode event = frame.json();

        Response now = timeline(sid, a, Map.of());
        JsonNode typing = participant(now.body(), idb).get("typing");
        assertThat(typing.get("active").asBoolean()).isTrue();
        assertThat(typing.get("expiresAt").asString()).isEqualTo(event.get("expiresAt").asString());
        assertThat(now.header("Cache-Control")).contains("no-store");

        Thread.sleep(11_000);
        JsonNode expired = participant(timeline(a, Map.of()), idb).get("typing");
        assertThat(expired.get("active").asBoolean()).isFalse();
        assertThat(expired.get("expiresAt").isNull()).isTrue();

        JsonNode atTyping = timeline(a, Map.of("atSeq", event.get("seq").asLong()));
        assertThat(participant(atTyping, idb).get("typing").get("active").asBoolean()).as("그 시점에는 켜져 있다").isTrue();
        assertThat(atTyping.get("asOf").get("referenceTime").asString()).isEqualTo(event.get("serverTime").asString());

        api.post("/sessions/" + sid + "/events", Map.of("type", "TYPING_STARTED"), b);
        message(b, "보냅니다");
        assertThat(participant(timeline(a, Map.of()), idb).get("typing").get("active").asBoolean()).isFalse();
    }

    @Test
    @Order(5)
    @DisplayName("presence·안 읽은 수·읽음 커서")
    void unreadAndReadCursor() {
        message(a, "확인 부탁드립니다");
        message(b, "네");
        message(b, "확인했습니다");
        JsonNode view = timeline(a, Map.of());
        assertThat(participant(view, ida).get("presence").get("status").asString()).isEqualTo("ONLINE");
        assertThat(participant(view, ida).get("unreadCount").asInt()).as("A의 마지막 메시지 뒤 B 메시지 수").isEqualTo(2);
        assertThat(participant(view, idb).get("unreadCount").asInt()).isZero();

        long head = view.get("asOf").get("seq").asLong();
        api.post("/sessions/" + sid + "/events", Map.of("type", "READ_CURSOR", "payload", Map.of("upToSeq", head)), a);
        JsonNode read = participant(timeline(a, Map.of()), ida);
        assertThat(read.get("unreadCount").asInt()).isZero();
        assertThat(read.get("readCursor").get("seq").asLong()).isEqualTo(head);
    }

    @Test
    @Order(6)
    @DisplayName("시각 지정: 생성 시각은 WAITING, 생성 전은 422, 60초 안 미래는 현재로, 먼 미래와 오프셋 없는 값은 400")
    void atTime() {
        JsonNode current = timeline(a, Map.of());
        String created = current.get("session").get("createdAt").asString();

        JsonNode atCreated = timeline(a, Map.of("at", created)).get("asOf");
        assertThat(atCreated.get("seq").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(atCreated.get("status").asString()).isEqualTo("WAITING");

        Response before = timeline(sid, a, Map.of("at", Instant.parse(created).minusSeconds(1).toString()));
        assertThat(before.status()).isEqualTo(422);
        assertThat(before.code()).isEqualTo("BEFORE_SESSION_START");

        JsonNode soon = timeline(a, Map.of("at", Instant.now().plusSeconds(30).toString())).get("asOf");
        assertThat(soon.get("seq").asLong()).isEqualTo(current.get("asOf").get("seq").asLong());
        assertThat(timeline(sid, a, Map.of("at", Instant.now().plusSeconds(120).toString())).code()).isEqualTo("AT_IN_FUTURE");
        assertThat(timeline(sid, a, Map.of("at", "2026-10-21T10:00:00")).code()).isEqualTo("INVALID_TIME_FORMAT");
        assertThat(timeline(sid, a, Map.of("at", created, "atSeq", 3)).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @Order(7)
    @DisplayName("화면 항목: 최근 N건을 seq 오름차순으로, typing·읽음 커서는 빼고, beforeSeq로 이전 페이지")
    void items() {
        JsonNode page = timeline(a, Map.of("itemLimit", 10)).get("items");
        List<Long> seqs = page.get("list").valueStream().map(i -> i.get("seq").asLong()).toList();
        assertThat(seqs).hasSize(10).isSorted();
        assertThat(page.get("hasMore").asBoolean()).isTrue();
        assertThat(page.get("nextBeforeSeq").asLong()).isEqualTo(seqs.getFirst());
        assertThat(page.get("list").valueStream().map(i -> i.get("type").asString()))
                .doesNotContain("TYPING_STARTED", "TYPING_STOPPED", "READ_CURSOR");

        JsonNode previous = timeline(a, Map.of("itemLimit", 10, "beforeSeq", page.get("nextBeforeSeq").asLong())).get("items");
        List<Long> earlier = previous.get("list").valueStream().map(i -> i.get("seq").asLong()).toList();
        assertThat(earlier).hasSize(10);
        assertThat(earlier.getLast()).isLessThan(seqs.getFirst());
    }

    @Test
    @Order(8)
    @DisplayName("쓰기 측 현재 상태와 timeline 현재 상태가 같다(진행 중)")
    void writeSideMatchesWhileInProgress() {
        assertWriteSideMatchesTimeline();
    }

    @Test
    @Order(9)
    @DisplayName("권한: 비참여자 403, 퇴장자는 과거 시점도 403, 관리자는 참여하지 않아도 본다")
    void permissions() {
        assertThat(timeline(sid, c, Map.of()).code()).isEqualTo("NOT_PARTICIPANT");
        api.post("/sessions/" + sid + "/leave", null, b);
        assertThat(timeline(sid, b, Map.of("atSeq", 10)).code()).isEqualTo("PARTICIPANT_LEFT");
        assertThat(timeline(sid, admin, Map.of("atSeq", 50)).status()).isEqualTo(200);
    }

    @Test
    @Order(10)
    @DisplayName("종단 전이 때 자동 스냅샷이 마지막 seq까지 만들어져, 현재 복원은 리플레이 없이 끝난다")
    void terminalSnapshot() {
        long last = lastSeq();
        await().atMost(Duration.ofSeconds(20)).until(() -> Long.valueOf(last).equals(snapshotSeq(sid)));
        JsonNode view = timeline(a, Map.of());
        assertThat(view.get("restoredFrom").get("mode").asString()).isEqualTo("SNAPSHOT_INCREMENTAL");
        assertThat(view.get("restoredFrom").get("replayedEvents").asLong()).isZero();
        assertThat(view.get("asOf").get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(participant(view, idb).get("state").asString()).isEqualTo("LEFT");
        assertWriteSideMatchesTimeline();
    }

    @Test
    @DisplayName("모르는 스키마 버전이나 깨진 payload는 건너뛰지 않고 422 REPLAY_UNSUPPORTED(seq 포함)")
    void replayUnsupported() {
        String unknownSchema = api.post("/sessions", Map.of("title", "리플레이 불가"), c).text("id");
        OwnerDb.update("UPDATE session_event SET schema_version = 99 WHERE session_id = '" + unknownSchema + "' AND seq = 2");
        String brokenPayload = api.post("/sessions", Map.of("title", "payload 결함"), c).text("id");
        OwnerDb.update("UPDATE session_event SET payload = '{}'::jsonb WHERE session_id = '" + brokenPayload + "' AND seq = 2");

        for (String session : List.of(unknownSchema, brokenPayload)) {
            Response response = timeline(session, admin, Map.of("ignoreSnapshot", true));
            assertThat(response.status()).as(session).isEqualTo(422);
            assertThat(response.code()).isEqualTo("REPLAY_UNSUPPORTED");
            assertThat(response.body().get("seq").asLong()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("형식이 틀린 요청과 없는 세션 요청은 재시도 없이 DLT로 간다(예외 정보 헤더 포함)")
    void deadLetter() {
        String unknown = UUID.randomUUID().toString();
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, ChatContainers.KAFKA.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
            producer.send(new ProducerRecord<>("chat.snapshot.requests", "not-json"));
            producer.send(new ProducerRecord<>("chat.snapshot.requests", unknown,
                    "{\"sessionId\":\"" + unknown + "\",\"upToSeq\":1}"));
        }

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, ChatContainers.KAFKA.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "it-dlt-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> dead = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of("chat.snapshot.requests.DLT"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(dead::add);
                return dead.stream().anyMatch(r -> "not-json".equals(r.value()))
                        && dead.stream().anyMatch(r -> r.value() != null && r.value().contains(unknown));
            });
        }
        ConsumerRecord<String, String> missing = dead.stream()
                .filter(r -> r.value() != null && r.value().contains(unknown)).findFirst().orElseThrow();
        assertThat(missing.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        assertThat(new String(missing.headers().lastHeader("kafka_dlt-original-topic").value(), StandardCharsets.UTF_8))
                .isEqualTo("chat.snapshot.requests");
    }
}
