package com.eventchat.stomp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
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
    private final TaskScheduler stompTaskScheduler;

    public StompConfig(StompProperties properties, StompInboundInterceptor inboundInterceptor,
            StompErrorHandler errorHandler, @Qualifier("stompTaskScheduler") TaskScheduler stompTaskScheduler) {
        this.properties = properties;
        this.inboundInterceptor = inboundInterceptor;
        this.errorHandler = errorHandler;
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

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit((int) properties.messageSizeLimit().toBytes())
                .setSendBufferSizeLimit((int) properties.sendBufferSizeLimit().toBytes())
                .setSendTimeLimit((int) properties.sendTimeLimit().toMillis())
                .setTimeToFirstMessage((int) properties.timeToFirstMessage().toMillis());
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
