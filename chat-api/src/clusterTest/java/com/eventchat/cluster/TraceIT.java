package com.eventchat.cluster;

import static com.eventchat.testsupport.StompClient.onSubscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventchat.testsupport.Api;
import com.eventchat.testsupport.Api.Response;
import com.eventchat.testsupport.StompClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 관측 스택(--profile obs, OTEL_ENABLED=true)을 함께 띄운 클러스터에서 REST 요청 하나가
 * DB·Redis를 거쳐 다른 인스턴스의 실시간 전달까지 한 트레이스로 이어지는지 Tempo에서 확인한다.
 */
@DisplayName("분산 추적")
@Tag("obs")
class TraceIT {

    private static final String GRAFANA = System.getProperty("cluster.grafanaUrl", "http://127.0.0.1:3000");

    private record Span(String instance, String name) {
    }

    @Test
    @DisplayName("REST 요청 → JDBC·Redis PUBLISH → 다른 인스턴스의 realtime deliver가 한 트레이스에 있다")
    void restToRemoteDelivery() {
        Api api = new Api(ClusterIT.BASE_URL);
        Api grafana = new Api(GRAFANA);
        String a = api.token("telecom.user");
        String b = api.token("telecom.customer");
        StompClient ca = ClusterIT.connect(a, null, null);
        StompClient cb = ClusterIT.connect(b, ClusterIT.ip(ca), null);
        try {
            String sid = api.post("/sessions", Map.of("title", "트레이스 확인"), a).text("id");
            api.post("/sessions/" + sid + "/join", null, b);
            cb.subscribe("/topic/sessions/" + sid, "s1");
            ClusterIT.sleep(500);

            String marker = "trace-" + UUID.randomUUID().toString().substring(0, 8);
            Response sent = api.post("/sessions/" + sid + "/events",
                    Map.of("type", "MESSAGE", "clientEventId", UUID.randomUUID(), "payload", Map.of("content", marker)), a);
            assertThat(sent.status()).isEqualTo(201);
            assertThat(cb.expect(onSubscription("s1", j -> marker.equals(j.path("payload").path("content").asString(null))),
                    Duration.ofSeconds(5))).isNotNull();

            // Tempo는 몇 초 단위로 묶어 받으므로 realtime deliver 스팬이 있는 트레이스가 나타날 때까지 기다린다.
            AtomicReference<List<Span>> trace = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(3)).until(() -> {
                long now = Instant.now().getEpochSecond();
                JsonNode found = grafana.get("/api/datasources/proxy/uid/tempo/api/search", null, Map.of(
                        "q", "{ name = \"realtime deliver\" }", "limit", 20, "start", now - 300, "end", now + 60)).body();
                for (JsonNode summary : found.path("traces")) {
                    List<Span> spans = spans(grafana.get("/api/datasources/proxy/uid/tempo/api/traces/"
                            + summary.get("traceID").asString(), null).body());
                    if (spans.stream().anyMatch(s -> s.name().toLowerCase().contains("post")
                            && s.name().toLowerCase().contains("events"))) {
                        trace.set(spans);
                        return true;
                    }
                }
                return false;
            });

            List<Span> spans = trace.get();
            Set<String> http = new HashSet<>();
            Set<String> deliver = new HashSet<>();
            spans.forEach(s -> {
                if (s.name().toLowerCase().contains("post")) {
                    http.add(s.instance());
                }
                if (s.name().equals("realtime deliver")) {
                    deliver.add(s.instance());
                }
            });
            assertThat(http).isNotEmpty();
            assertThat(spans).anyMatch(s -> s.name().startsWith("jdbc") || s.name().toLowerCase().contains("query"));
            assertThat(spans).anyMatch(s -> s.name().toLowerCase().contains("publish"));
            Set<String> instances = new HashSet<>(http);
            instances.addAll(deliver);
            assertThat(deliver).isNotEmpty();
            assertThat(instances).as("받는 노드가 다른 인스턴스").hasSizeGreaterThanOrEqualTo(2);
        } finally {
            ca.close();
            cb.close();
        }
    }

    /** OTLP JSON(batches 또는 resourceSpans)에서 (service.instance.id, 스팬 이름)을 꺼낸다. */
    private static List<Span> spans(JsonNode trace) {
        List<Span> spans = new ArrayList<>();
        JsonNode batches = trace.has("batches") ? trace.get("batches") : trace.path("resourceSpans");
        for (JsonNode batch : batches) {
            String instance = null;
            for (JsonNode attribute : batch.path("resource").path("attributes")) {
                if (attribute.get("key").asString().equals("service.instance.id")) {
                    instance = attribute.get("value").path("stringValue").asString(null);
                }
            }
            JsonNode scopes = batch.has("scopeSpans") ? batch.get("scopeSpans") : batch.path("instrumentationLibrarySpans");
            for (JsonNode scope : scopes) {
                for (JsonNode span : scope.path("spans")) {
                    spans.add(new Span(instance, span.get("name").asString()));
                }
            }
        }
        return spans;
    }
}
