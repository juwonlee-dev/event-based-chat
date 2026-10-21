package com.eventchat.worker;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 스냅샷 갱신 요청 토픽. 브로커의 자동 생성을 끄고 앱 기동 시 KafkaAdmin이 만든다.
 */
@Configuration(proxyBeanMethods = false)
public class SnapshotTopics {

    public static final String REQUESTS = "chat.snapshot.requests";
    public static final String REQUESTS_DLT = REQUESTS + ".DLT";

    @Bean
    NewTopic snapshotRequestsTopic() {
        return TopicBuilder.name(REQUESTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic snapshotRequestsDltTopic() {
        return TopicBuilder.name(REQUESTS_DLT).partitions(1).replicas(1).build();
    }
}
