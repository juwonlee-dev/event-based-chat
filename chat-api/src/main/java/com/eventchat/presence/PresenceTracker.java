package com.eventchat.presence;

import com.eventchat.domain.session.PresenceStatus;
import com.eventchat.domain.session.SessionCommand;
import com.eventchat.domain.session.SessionStatus;
import com.eventchat.persistence.session.PresenceRow;
import com.eventchat.persistence.session.SessionParticipantRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 이 노드의 연결을 Redis에 등록하고 사용자 단위 연결 상태(presence) 전이를 받는다.
 * 판정(연결 수 0↔1, 유예, ver)은 {@code redis/presence.lua}가 원자적으로 한다.
 * <ul>
 *   <li>연결·해제: CONNECT 수락과 연결 종료 때 그 스레드에서 바로 부른다(같은 연결의 등록·해제 순서를 지킨다).</li>
 *   <li>노드 lease: nodeHeartbeat마다 {@code SET XX}로 갱신한다. lease를 잃었거나, Redis가 재시작했거나(run_id),
 *       Redis 호출이 실패한 적이 있거나, 구독 연결이 다시 붙으면 메모리의 연결을 모두 다시 등록한다.</li>
 *   <li>스위퍼: 모든 노드가 sweepInterval마다 돌린다. 락 없이 Lua로 꺼내므로 한 사용자의 전이는 한 번만 나온다.</li>
 *   <li>대조: reconcileInterval마다 DB 기록과 실제 연결 상태를 양방향으로 맞춘다.</li>
 * </ul>
 * Redis가 실패하면 기록이 멈추고, 복구 뒤 재등록·대조로 맞춘다. 쓰기 성공 조건과 readiness에는 넣지 않는다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(PresenceProperties.class)
public class PresenceTracker implements SmartLifecycle {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("redis/presence.lua"), List.class);
    private static final int SWEEP_BATCH = 500;
    private static final int RECONCILE_BATCH = 500;

    private final StringRedisTemplate redis;
    private final PresenceProperties properties;
    private final PresenceRecorder recorder;
    private final SessionParticipantRepository participants;
    private final MeterRegistry meters;
    private final String nodeId = newNodeId();
    /** 이 노드에 붙어 있는 연결("userId|connId"). 재등록의 기준이다. */
    private final Set<String> live = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile String redisRunId;
    private volatile boolean running;

    PresenceTracker(StringRedisTemplate redis, PresenceProperties properties, PresenceRecorder recorder,
            SessionParticipantRepository participants, MeterRegistry meters) {
        this.redis = redis;
        this.properties = properties;
        this.recorder = recorder;
        this.participants = participants;
        this.meters = meters;
    }

    public void connected(UUID userId, String connectionId) {
        String member = member(userId, connectionId);
        live.add(member);
        call(() -> handle(run("connect", userId.toString(), conn(connectionId), nodeId)));
    }

    public void disconnected(UUID userId, String connectionId, DisconnectReason reason) {
        live.remove(member(userId, connectionId));
        call(() -> run("disconnect", userId.toString(), conn(connectionId), nodeId, graceMicros(), reason.name()));
    }

    /**
     * 메모리의 연결을 Redis에 다시 등록한다(Redis에만 남은 이 노드의 연결은 해제).
     * 스크립트가 도는 동안 붙거나 끊긴 연결은 실행 뒤 한 번 더 맞춘다.
     */
    public synchronized void reregister(String trigger) {
        dirty.set(false);
        List<String> snapshot = List.copyOf(live);
        List<String> args = new ArrayList<>(List.of(nodeId, Long.toString(properties.nodeLeaseTtl().toMillis()),
                graceMicros()));
        args.addAll(snapshot);
        call(() -> {
            handle(run("register", args.toArray(String[]::new)));
            snapshot.stream().filter(member -> !live.contains(member)).forEach(member -> {
                String[] parts = member.split("\\|", 2);
                run("disconnect", parts[0], parts[1], nodeId, graceMicros(), DisconnectReason.SOCKET_CLOSED.name());
            });
            live.stream().filter(member -> !snapshot.contains(member)).forEach(member -> {
                String[] parts = member.split("\\|", 2);
                handle(run("connect", parts[0], parts[1], nodeId));
            });
            meters.counter("chat.presence.reregistered", "trigger", trigger).increment();
            log.info("presence re-registered {} connections: node={} trigger={}", snapshot.size(), nodeId, trigger);
        });
    }

    @Scheduled(fixedDelayString = "${chat.presence.node-heartbeat}",
            initialDelayString = "${chat.presence.node-heartbeat}")
    void heartbeat() {
        if (!running) {
            return;
        }
        String trigger;
        try {
            String runId = runId();
            boolean restarted = redisRunId != null && !redisRunId.equals(runId);
            redisRunId = runId;
            boolean renewed = Boolean.TRUE.equals(redis.opsForValue().setIfPresent(
                    "ws:node:" + nodeId, "1", properties.nodeLeaseTtl()));
            trigger = restarted ? "REDIS_RESTARTED" : !renewed ? "LEASE_LOST" : dirty.get() ? "REDIS_ERROR" : null;
        } catch (RuntimeException e) {
            markDirty(e);
            return;
        }
        if (trigger != null) {
            reregister(trigger);
        }
    }

    @Scheduled(fixedDelayString = "${chat.presence.sweep-interval}",
            initialDelayString = "${chat.presence.sweep-interval}")
    void sweep() {
        if (running) {
            call(() -> handle(run("sweep", graceMicros(), Integer.toString(SWEEP_BATCH))));
        }
    }

    /**
     * DB는 ONLINE인데 연결이 없고 유예 중도 아니면 DISCONNECT, DB는 OFFLINE인데 연결이 있으면 RECONNECT를
     * RECONCILED로 남긴다. 여러 노드가 돌려도 중복은 ver로 걸러진다.
     */
    @Scheduled(fixedDelayString = "${chat.presence.reconcile-interval}",
            initialDelayString = "${chat.presence.reconcile-interval}")
    void reconcile() {
        if (!running) {
            return;
        }
        Map<UUID, List<PresenceRow>> byUser = participants.findActivePresence(SessionStatus.IN_PROGRESS).stream()
                .collect(Collectors.groupingBy(PresenceRow::userId, LinkedHashMap::new, Collectors.toList()));
        List<UUID> users = List.copyOf(byUser.keySet());
        // 재등록은 lease 갱신 주기(heartbeat)마다 일어나므로, lease 두 배면 모든 노드가 다시 등록한 뒤다.
        String settleMicros = Long.toString(properties.nodeLeaseTtl().multipliedBy(2).toNanos() / 1000);
        for (int from = 0; from < users.size(); from += RECONCILE_BATCH) {
            List<String> args = new ArrayList<>();
            args.add(settleMicros);
            for (UUID userId : users.subList(from, Math.min(users.size(), from + RECONCILE_BATCH))) {
                List<PresenceRow> rows = byUser.get(userId);
                Set<PresenceStatus> states = rows.stream().map(PresenceRow::presence).collect(Collectors.toSet());
                args.add(userId.toString());
                args.add(states.size() == 1 ? states.iterator().next().name() : "MIXED");
                args.add(Long.toString(rows.stream().mapToLong(PresenceRow::presenceVer).max().orElse(0)));
            }
            call(() -> run("reconcile", args.toArray(String[]::new)).forEach(signal -> {
                PresenceStatus actual = PresenceStatus.valueOf(signal.kind());
                count(actual, "RECONCILED");
                SessionCommand.Presence command = new SessionCommand.Presence(signal.userId(), actual, signal.ver(),
                        Map.of("reason", "RECONCILED"));
                byUser.get(signal.userId()).stream()
                        .filter(row -> row.presence() != actual)
                        .forEach(row -> recorder.record(row.sessionId(), command));
            }));
        }
    }

    private void handle(List<Signal> signals) {
        for (Signal signal : signals) {
            switch (signal.kind()) {
                case "GRACE_CANCELLED" -> meters.counter("chat.presence.grace.cancelled").increment();
                case "ONLINE" -> {
                    // a: 오프라인이 된 시각(처음 연결이거나 Redis 재시작 뒤면 없음), b: 지금
                    Map<String, Object> detail = new LinkedHashMap<>();
                    if (!signal.a().isEmpty()) {
                        long offlineSince = Long.parseLong(signal.a());
                        detail.put("offlineSince", instant(offlineSince).toString());
                        detail.put("offlineMs", (Long.parseLong(signal.b()) - offlineSince) / 1000);
                    }
                    count(PresenceStatus.ONLINE, "CONNECTED");
                    recorder.record(new SessionCommand.Presence(signal.userId(), PresenceStatus.ONLINE, signal.ver(),
                            detail));
                }
                case "OFFLINE" -> {
                    // a: 끊긴 이유, b: 마지막 연결이 끊긴 시각
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("reason", signal.a());
                    detail.put("lastSeenAt", instant(Long.parseLong(signal.b())).toString());
                    count(PresenceStatus.OFFLINE, signal.a());
                    recorder.record(new SessionCommand.Presence(signal.userId(), PresenceStatus.OFFLINE, signal.ver(),
                            detail));
                }
                default -> log.warn("unknown presence signal: {}", signal);
            }
        }
    }

    private void count(PresenceStatus to, String reason) {
        meters.counter("chat.presence.transitions", "to", to.name(), "reason", reason).increment();
    }

    @SuppressWarnings("unchecked")
    private List<Signal> run(String op, String... args) {
        String[] argv = new String[args.length + 1];
        argv[0] = op;
        System.arraycopy(args, 0, argv, 1, args.length);
        return parse((List<String>) redis.execute(SCRIPT, List.of(), (Object[]) argv));
    }

    private static List<Signal> parse(List<String> flat) {
        List<Signal> signals = new ArrayList<>();
        for (int i = 0; i + 4 < flat.size(); i += 5) {
            String ver = flat.get(i + 2);
            signals.add(new Signal(UUID.fromString(flat.get(i)), flat.get(i + 1),
                    ver.isEmpty() ? 0 : Long.parseLong(ver), flat.get(i + 3), flat.get(i + 4)));
        }
        return signals;
    }

    private void call(Runnable redisWork) {
        try {
            redisWork.run();
        } catch (RuntimeException e) {
            markDirty(e);
        }
    }

    /** 실패한 등록·해제는 다음 lease 갱신 때 재등록으로 바로잡는다. */
    private void markDirty(RuntimeException e) {
        if (dirty.compareAndSet(false, true)) {
            log.warn("presence redis call failed, will re-register: node={}", nodeId, e);
        }
    }

    private String runId() {
        return redis.execute((RedisCallback<String>) connection ->
                connection.serverCommands().info("server").getProperty("run_id"));
    }

    private String member(UUID userId, String connectionId) {
        return userId + "|" + conn(connectionId);
    }

    /** STOMP 세션 ID는 노드 안에서만 유일하므로 nodeId를 붙인다. */
    private String conn(String connectionId) {
        return nodeId + "/" + connectionId;
    }

    private String graceMicros() {
        return Long.toString(properties.grace().toNanos() / 1000);
    }

    private static Instant instant(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS).truncatedTo(ChronoUnit.MILLIS);
    }

    /** 다시 기동한 노드가 이전 lease로 죽은 연결을 되살리지 않도록 기동마다 다른 값을 붙인다. */
    private static String newNodeId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "node";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void start() {
        running = true;
        try {
            redisRunId = runId();
        } catch (RuntimeException e) {
            markDirty(e);
        }
        reregister("STARTUP");
    }

    /** 웹 서버가 멈추고 남은 연결 종료가 끝난 뒤, 이 노드의 기록을 정리한다(남은 연결은 유예로 넘긴다). */
    @Override
    public void stop() {
        running = false;
        live.clear();
        call(() -> {
            run("register", nodeId, Long.toString(properties.nodeLeaseTtl().toMillis()), graceMicros());
            redis.delete("ws:node:" + nodeId);
            redis.opsForSet().remove("ws:nodes", nodeId);
        });
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 웹 서버(DEFAULT_PHASE - 2048)보다 먼저 시작하고 나중에 멈춘다. */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 4096;
    }

    private record Signal(UUID userId, String kind, long ver, String a, String b) {
    }
}
