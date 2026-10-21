package com.eventchat.realtime;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealtimeConfig {

    /** 끊겨 있는 동안 명령을 쌓아 두지 않는다. 오래된 명령이 재연결 뒤 늦게 실행되는 것을 막는다. */
    private static final int REQUEST_QUEUE_SIZE = 1024;

    @Bean
    RedisMessageListenerContainer realtimeListenerContainer(RedisConnectionFactory connectionFactory,
            PatternRealtimeBus bus, RealtimeControl control) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 기본 실행기는 메시지마다 새 스레드를 써서 같은 채널 안의 순서가 섞인다.
        container.setTaskExecutor(new SyncTaskExecutor());
        container.addMessageListener(bus, new PatternTopic(PatternRealtimeBus.PATTERN));
        container.addMessageListener(control, new ChannelTopic(RealtimeControl.CHANNEL));
        return container;
    }

    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return options -> options
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(REQUEST_QUEUE_SIZE);
    }
}
