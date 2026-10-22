package com.eventchat.it.support;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 통합 테스트가 함께 쓰는 PostgreSQL·Redis·Kafka. compose.yaml과 같은 이미지와 같은 설정 파일을 쓴다
 * (DB 역할 분리와 권한, Redis ACL). JVM에서 한 번만 띄우고 모든 테스트 클래스가 공유한다.
 */
public final class ChatContainers {

    public static final String DB_NAME = "chat";
    public static final String DB_OWNER = "chat_owner";
    public static final String DB_OWNER_PASSWORD = "owner-secret";
    public static final String DB_APP = "chat_app";
    public static final String DB_APP_PASSWORD = "app-secret";
    public static final String REDIS_USER = "chat-app";
    public static final String REDIS_PASSWORD = "redis-secret";

    private static final Path ROOT = Path.of(System.getProperty("repo.root", ".."));

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withEnv("DB_NAME", DB_NAME)
            .withEnv("DB_OWNER_USER", DB_OWNER)
            .withEnv("DB_OWNER_PASSWORD", DB_OWNER_PASSWORD)
            .withEnv("DB_APP_USER", DB_APP)
            .withEnv("DB_APP_PASSWORD", DB_APP_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("docker/postgres-init/01-roles.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-roles.sh")
            .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=200", "-c", "log_lock_waits=on");

    /**
     * 재시작 시험에서 호스트 포트가 바뀌면 앱이 다시 붙지 못하므로 빈 포트를 골라 고정해 둔다.
     * 앱 계정 ACL은 compose와 같다.
     */
    public static final int REDIS_PORT = freePort();
    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine")
            .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("docker/redis/redis.conf")),
                    "/usr/local/etc/redis/redis.conf")
            .withCommand(List.of(
                    "redis-server", "/usr/local/etc/redis/redis.conf",
                    "--user", "default", "off",
                    "--user", REDIS_USER, "on", ">" + REDIS_PASSWORD,
                    "~ws:*", "~pr:*", "~rl:*", "&chat:s:*", "&chat:ctl",
                    "-@all", "+@read", "+@write", "+@pubsub", "+@scripting", "+@connection", "-@dangerous",
                    "+ping", "+info", "+time").toArray(String[]::new))
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))))
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

    private ChatContainers() {
    }

    public static synchronized void start() {
        if (!POSTGRES.isRunning()) {
            Startables.deepStart(POSTGRES, REDIS, KAFKA).join();
        }
    }

    public static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + DB_NAME;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
