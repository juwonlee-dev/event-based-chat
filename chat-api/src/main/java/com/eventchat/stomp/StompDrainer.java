package com.eventchat.stomp;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 종료할 때 로컬 연결을 drainWindow에 걸쳐 1012(Service Restart)로 나눠 닫는다.
 * 한꺼번에 닫으면 클라이언트가 동시에 다른 노드로 몰린다.
 * <p>
 * 순서: readiness REFUSING_TRAFFIC(컨텍스트 종료 이벤트) → 이 드레이닝 → 브로커·WebSocket 핸들러 → 웹 서버.
 */
@Slf4j
@Component
public class StompDrainer implements SmartLifecycle {

    private final StompConnections connections;
    private final Duration drainWindow;
    private volatile boolean running;

    public StompDrainer(StompConnections connections, StompProperties properties) {
        this.connections = connections;
        this.drainWindow = properties.drainWindow();
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @Override
    public void stop(Runnable callback) {
        running = false;
        List<WebSocketSession> sockets = connections.openSockets();
        if (sockets.isEmpty()) {
            callback.run();
            return;
        }
        log.info("draining {} websocket connections over {}", sockets.size(), drainWindow);
        CompletableFuture.allOf(sockets.stream()
                        .map(socket -> CompletableFuture.runAsync(() -> close(socket), CompletableFuture.delayedExecutor(
                                ThreadLocalRandom.current().nextLong(drainWindow.toMillis() + 1), TimeUnit.MILLISECONDS)))
                        .toArray(CompletableFuture[]::new))
                .whenComplete((ignored, ex) -> callback.run());
    }

    private static void close(WebSocketSession socket) {
        try {
            if (socket.isOpen()) {
                socket.close(CloseStatus.SERVICE_RESTARTED);
            }
        } catch (IOException e) {
            log.debug("failed to close websocket {}", socket.getId(), e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 브로커·WebSocket 핸들러(DEFAULT_PHASE - 512)보다 먼저 멈춘다. */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE;
    }
}
