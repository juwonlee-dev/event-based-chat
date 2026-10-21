package com.eventchat.stomp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * STOMP over WebSocket(/ws). SockJS는 쓰지 않는다.
 * <ul>
 *   <li>heart-beat: 서버 10s를 보내고 클라이언트 5s를 요구한다. 스케줄러를 지정해야 heartbeat가 켜진다.</li>
 *   <li>같은 연결 안에서 받은 순서·보내는 순서를 지킨다.</li>
 *   <li>인바운드 처리는 DB 블로킹 I/O를 하므로 가상 스레드 실행기에 동시성 상한을 둔다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(StompProperties.class)
public class StompConfig implements WebSocketMessageBrokerConfigurer {

    private final StompProperties properties;
    private final StompInboundInterceptor inboundInterceptor;
    private final StompErrorHandler errorHandler;
    private final StompConnections connections;
    private final TaskScheduler stompTaskScheduler;

    public StompConfig(StompProperties properties, StompInboundInterceptor inboundInterceptor,
            StompErrorHandler errorHandler, StompConnections connections,
            @Qualifier("stompTaskScheduler") TaskScheduler stompTaskScheduler) {
        this.properties = properties;
        this.inboundInterceptor = inboundInterceptor;
        this.errorHandler = errorHandler;
        this.connections = connections;
        this.stompTaskScheduler = stompTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
        registry.setErrorHandler(errorHandler);
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {
                        properties.serverHeartbeat().toMillis(), properties.clientHeartbeat().toMillis()})
                .setTaskScheduler(stompTaskScheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("stomp-in-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(properties.inboundConcurrency());
        registration.executor(executor).interceptors(inboundInterceptor);
    }

    /**
     * 브로커가 heart-beat 누락으로 연결을 닫을 때는 클라이언트 DISCONNECT 없이 DISCONNECT_ACK를 보낸다.
     * 그것으로 끊긴 이유(HEARTBEAT_TIMEOUT)를 구분한다.
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.DISCONNECT_ACK
                        && message.getHeaders().get(SimpMessageHeaderAccessor.DISCONNECT_MESSAGE_HEADER) == null) {
                    connections.heartbeatTimedOut(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
                }
                return message;
            }
        });
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit((int) properties.messageSizeLimit().toBytes())
                .setSendBufferSizeLimit((int) properties.sendBufferSizeLimit().toBytes())
                .setSendTimeLimit((int) properties.sendTimeLimit().toMillis())
                .setTimeToFirstMessage((int) properties.timeToFirstMessage().toMillis())
                .addDecoratorFactory(connections::decorate);
    }

    /** 드레이닝({@link StompDrainer})이 먼저 연결을 닫은 뒤에 브로커·WebSocket 핸들러가 멈추도록 한 단계 늦춘다. */
    @Override
    public Integer getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 512;
    }

    /** heartbeat, 토큰 만료 종료, 드레이닝 예약에 쓴다. */
    @Bean
    static ThreadPoolTaskScheduler stompTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("stomp-sched-");
        return scheduler;
    }

    /** Tomcat 기본 수신 버퍼(8KB)로는 큰 프레임에서 연결이 1009로 끊긴다. 프레임 한도와 맞춘다. */
    @Bean
    static ServletServerContainerFactoryBean webSocketContainer(StompProperties properties) {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize((int) properties.messageSizeLimit().toBytes());
        container.setMaxBinaryMessageBufferSize((int) properties.messageSizeLimit().toBytes());
        return container;
    }
}
