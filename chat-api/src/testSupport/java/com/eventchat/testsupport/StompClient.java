package com.eventchat.testsupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import tools.jackson.databind.JsonNode;

/**
 * 테스트용 STOMP 클라이언트. 프레임을 직접 만들고 읽는다.
 * 라이브러리 클라이언트로는 하기 어려운 것들(heart-beat를 멈춘 채 연결 유지, 임의 헤더, 허용 목록 밖 목적지,
 * 핸드셰이크 응답 헤더 확인)을 시험해야 해서 직접 만들었다.
 */
public final class StompClient implements AutoCloseable {

    private static final OkHttpClient HTTP = new OkHttpClient.Builder().readTimeout(Duration.ZERO).build();

    private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
    private final List<Frame> backlog = new ArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "stomp-test-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final WebSocket socket;
    private volatile boolean heartbeatPaused;
    private volatile Integer closeCode;
    private volatile String upstream;
    private Frame connected;

    private StompClient(String url) {
        CountDownLatch opened = new CountDownLatch(1);
        socket = HTTP.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                upstream = response.header("X-Upstream");
                opened.countDown();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (!text.isBlank()) {
                    frames.add(Frame.parse(text));
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                closeCode = code;
                webSocket.close(code, null);
                closed.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                closeCode = code;
                closed.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                opened.countDown();
                closed.countDown();
            }
        });
        await(opened, Duration.ofSeconds(10));
    }

    /** WebSocket만 연다(CONNECT 없이). */
    public static StompClient open(String wsUrl) {
        return new StompClient(wsUrl);
    }

    /** CONNECT까지 보낸다. heart-beat 기본값은 클라이언트 1초 / 서버 10초다. */
    public static StompClient connect(String wsUrl, String token) {
        return connect(wsUrl, token, "1000,10000", Map.of());
    }

    public static StompClient connect(String wsUrl, String token, String heartbeat, Map<String, String> extra) {
        StompClient client = new StompClient(wsUrl);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept-version", "1.2");
        headers.put("host", "localhost");
        headers.put("heart-beat", heartbeat);
        if (token != null) {
            headers.put("Authorization", "Bearer " + token);
        }
        headers.putAll(extra);
        client.frame("CONNECT", headers, "");
        client.connected = client.expect(f -> f.command().equals("CONNECTED") || f.command().equals("ERROR"),
                Duration.ofSeconds(10));
        client.startHeartbeat();
        return client;
    }

    private void startHeartbeat() {
        heartbeat.scheduleAtFixedRate(() -> {
            if (!heartbeatPaused) {
                socket.send("\n");
            }
        }, 400, 400, TimeUnit.MILLISECONDS);
    }

    /** heart-beat만 멈추고 소켓은 열어 둔다(프로세스가 멈춘 클라이언트와 같은 상태). */
    public void pauseHeartbeat() {
        heartbeatPaused = true;
    }

    public Frame connected() {
        return connected;
    }

    public boolean isConnected() {
        return connected != null && connected.command().equals("CONNECTED");
    }

    /** 이 연결을 처리한 인스턴스(nginx가 붙이는 X-Upstream). nginx를 거치지 않으면 null */
    public String upstream() {
        return upstream;
    }

    public void frame(String command, Map<String, String> headers, String body) {
        StringBuilder text = new StringBuilder(command).append('\n');
        headers.forEach((k, v) -> text.append(k).append(':').append(v).append('\n'));
        text.append('\n').append(body).append('\0');
        socket.send(text.toString());
    }

    public void subscribe(String destination, String id) {
        subscribe(destination, id, Map.of());
    }

    public void subscribe(String destination, String id, Map<String, String> extra) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("id", id);
        headers.put("destination", destination);
        headers.putAll(extra);
        frame("SUBSCRIBE", headers, "");
    }

    public void send(String destination, Object body) {
        frame("SEND", Map.of("destination", destination, "content-type", "application/json"),
                body instanceof String s ? s : Api.JSON.writeValueAsString(body));
    }

    /** 조건에 맞는 프레임을 기다린다. 맞지 않는 프레임은 버리지 않고 쌓아 두었다가 다음 기다림에서 먼저 본다. */
    public Frame expect(Predicate<Frame> condition, Duration timeout) {
        for (Iterator<Frame> it = backlog.iterator(); it.hasNext(); ) {
            Frame frame = it.next();
            if (condition.test(frame)) {
                it.remove();
                return frame;
            }
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return null;
            }
            Frame frame;
            try {
                frame = frames.poll(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (frame == null) {
                return null;
            }
            if (condition.test(frame)) {
                return frame;
            }
            backlog.add(frame);
        }
    }

    /** 지금까지 받은 프레임을 모두 꺼낸다(잠깐 기다린 뒤). */
    public List<Frame> drain(Duration wait) {
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        frames.drainTo(backlog);
        List<Frame> all = new ArrayList<>(backlog);
        backlog.clear();
        return all;
    }

    public boolean awaitClosed(Duration timeout) {
        return await(closed, timeout);
    }

    public boolean isClosed() {
        return closed.getCount() == 0;
    }

    public Integer closeCode() {
        return closeCode;
    }

    @Override
    public void close() {
        heartbeat.shutdownNow();
        socket.close(1000, null);
    }

    private static boolean await(CountDownLatch latch, Duration timeout) {
        try {
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 세션 구독(subscription 헤더 = id)으로 온 MESSAGE */
    public static Predicate<Frame> onSubscription(String id) {
        return f -> f.command().equals("MESSAGE") && id.equals(f.headers().get("subscription"));
    }

    public static Predicate<Frame> onSubscription(String id, Predicate<JsonNode> body) {
        return f -> onSubscription(id).test(f) && body.test(f.json());
    }

    public record Frame(String command, Map<String, String> headers, String body) {

        static Frame parse(String text) {
            int split = text.indexOf("\n\n");
            String head = split < 0 ? text : text.substring(0, split);
            String body = split < 0 ? "" : text.substring(split + 2);
            int nul = body.indexOf('\0');
            if (nul >= 0) {
                body = body.substring(0, nul);
            }
            String[] lines = head.strip().split("\n");
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.putIfAbsent(lines[i].substring(0, colon), lines[i].substring(colon + 1));
                }
            }
            return new Frame(lines[0], headers, body);
        }

        public JsonNode json() {
            return body.isBlank() ? null : Api.JSON.readTree(body);
        }

        public String message() {
            return headers.get("message");
        }
    }
}
