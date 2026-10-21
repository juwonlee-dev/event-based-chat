package com.eventchat.worker;

import com.eventchat.web.error.ApiException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import tools.jackson.core.JacksonException;

/**
 * 소비 실패 처리. 일시적인 실패(DB 잠금·연결 등)는 1s부터 2배씩 최대 30s 간격으로 5번 다시 시도하고(blocking),
 * 그래도 실패하거나 다시 해도 소용없는 실패(형식 오류, 없는 세션, 복원 불가)는 바로 DLT로 보낸다.
 * DLT에 쌓인 세션은 관리자 POST /sessions/{id}/snapshots로 다시 만든다.
 */
@Configuration(proxyBeanMethods = false)
class KafkaWorkerConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> new TopicPartition(SnapshotTopics.REQUESTS_DLT, -1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(5);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2);
        backOff.setMaxInterval(30_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class, ApiException.class);
        return handler;
    }
}
