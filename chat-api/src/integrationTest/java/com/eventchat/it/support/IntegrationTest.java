package com.eventchat.it.support;

import com.eventchat.ChatApplication;
import com.eventchat.testsupport.Api;
import com.eventchat.testsupport.Api.Response;
import com.eventchat.testsupport.StompClient;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 통합 테스트의 공통 기반. 앱을 임의 포트로 한 번 띄우고(모든 테스트 클래스가 같은 컨텍스트를 공유),
 * 컨테이너는 {@link ChatContainers}가 맡는다. 로컬 프로필이라 시드 사용자가 들어 있다.
 * <p>
 * presence 관련 시간은 몇 초 안에 확인할 수 있게 줄였다: 유예 3초, 스위퍼 0.5초, 대조 5초,
 * 클라이언트 heart-beat 1초(3초 동안 프레임이 없으면 서버가 끊는다).
 */
@SpringBootTest(classes = ChatApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "chat.jwt.secret=" + IntegrationTest.JWT_SECRET,
        "chat.logging.dir=build/integration-test-logs",
        "chat.presence.grace=3s",
        "chat.presence.node-heartbeat=1s",
        "chat.presence.node-lease-ttl=3s",
        "chat.presence.sweep-interval=500ms",
        "chat.presence.reconcile-interval=5s",
        "chat.stomp.client-heartbeat=1s",
})
@ActiveProfiles("local")
public abstract class IntegrationTest {

    public static final String JWT_SECRET = "integration-test-signing-key-0123456789abcdef";

    @LocalServerPort
    protected int port;

    @LocalManagementPort
    protected int managementPort;

    protected Api api;
    protected Api management;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        ChatContainers.start();
        registry.add("spring.datasource.url", ChatContainers::jdbcUrl);
        registry.add("spring.datasource.username", () -> ChatContainers.DB_APP);
        registry.add("spring.datasource.password", () -> ChatContainers.DB_APP_PASSWORD);
        registry.add("spring.flyway.user", () -> ChatContainers.DB_OWNER);
        registry.add("spring.flyway.password", () -> ChatContainers.DB_OWNER_PASSWORD);
        registry.add("spring.data.redis.host", ChatContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> ChatContainers.REDIS_PORT);
        registry.add("spring.data.redis.username", () -> ChatContainers.REDIS_USER);
        registry.add("spring.data.redis.password", () -> ChatContainers.REDIS_PASSWORD);
        registry.add("spring.kafka.bootstrap-servers", ChatContainers.KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void clients() {
        api = new Api("http://localhost:" + port);
        management = new Api("http://localhost:" + managementPort);
    }

    protected String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    protected StompClient stomp(String username) {
        return StompClient.connect(wsUrl(), api.token(username));
    }

    protected static String topic(Object sessionId) {
        return "/topic/sessions/" + sessionId;
    }

    protected static String app(Object sessionId) {
        return "/app/sessions/" + sessionId + "/events";
    }

    /** 같은 요청을 동시에 n번 보낸다(시작 시점을 맞춘다). */
    protected static List<Response> concurrently(int n, IntFunction<Response> request) {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<Response>> futures = IntStream.range(0, n)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return request.apply(i);
                    }))
                    .toList();
            start.countDown();
            return futures.stream().map(f -> {
                try {
                    return f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
    }
}
