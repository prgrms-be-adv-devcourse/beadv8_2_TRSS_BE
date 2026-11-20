package com.backend.global.kafka;

import com.backend.global.outbox.OutboxPolicy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import tools.jackson.core.JacksonException;

import java.time.DateTimeException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 모든 @KafkaListener에 적용되는 실패 처리(스프링 부트가 이 DefaultErrorHandler 빈을 기본 컨테이너에 붙인다).
 *
 * 리스너가 예외를 던지면 같은 메시지를 간격을 늘려 가며 다시 처리한다(KafkaConsumerPolicy, custom.kafka.consumer.*).
 * 기본값은 1초, 2초, 4초, 8초 간격으로 4번 더 처리한다(총 5번, 대기 합 15초).
 * 재시도하는 동안 그 파티션의 뒤 메시지는 기다리므로, 같은 대상의 이벤트 순서가 지켜진다.
 * 그래도 실패하면 버리지 않고 dead-letter 토픽(<원래 토픽>-dlt)으로 보내고 ERROR 로그를 남긴다(→ Slack 알림).
 * DLT 파티션은 메시지 키로 정하므로 같은 대상의 메시지는 DLT에서도 같은 파티션에 모인다.
 * 메시지가 깨져 다시 해도 안 되는 오류(JSON 파싱 실패, 봉투 필드 누락·모르는 이벤트 타입 등)는 재시도 없이 바로 DLT로 보낸다.
 *
 * DLT 메시지는 자동으로 다시 처리하지 않는다. 원인을 고친 뒤 원래 토픽으로 다시 보내면 된다.
 * 컨슈머는 eventId로 이미 처리한 메시지를 거르므로(processed_event) 다시 보내도 두 번 처리되지 않는다.
 * DLT로 보내는 것 자체가 실패하면(브로커 장애 등) offset을 넘기지 않아 메시지를 다시 받는다.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    static final String DLT_SUFFIX = "-dlt";

    // 브로커가 1대라 복제 수는 1 (OutboxKafkaSender와 같음)
    private static final short TOPIC_REPLICAS = 1;

    private final Set<String> readyTopics = ConcurrentHashMap.newKeySet();

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate, KafkaAdmin kafkaAdmin,
                                                 OutboxPolicy outboxPolicy, KafkaConsumerPolicy consumerPolicy) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> {
                    String topic = record.topic() + DLT_SUFFIX;
                    ensureTopic(kafkaAdmin, topic, outboxPolicy.getTopicPartitions());
                    // 파티션을 지정하지 않고(-1) 키로 정한다. 원래 토픽과 파티션 수가 달라져도 보내기에 실패하지 않는다
                    return new TopicPartition(topic, -1);
                });

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(consumerPolicy.getMaxRetries());
        backOff.setInitialInterval(consumerPolicy.getRetryInitialIntervalMillis());
        backOff.setMultiplier(consumerPolicy.getRetryMultiplier());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, ex) -> {
            deadLetter.accept(record, ex);   // 보내기에 실패하면 예외가 나서 offset을 넘기지 않는다
            logDeadLetter(record, ex);
        }, backOff);
        errorHandler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class, DateTimeException.class);
        return errorHandler;
    }

    // 운영 알림(Grafana → Slack)은 ERROR 로그로 걸린다
    private static void logDeadLetter(ConsumerRecord<?, ?> record, Exception ex) {
        log.error("Kafka 메시지 처리에 실패해 DLT로 보냈습니다. 원인을 고친 뒤 원래 토픽으로 다시 보내야 합니다. dlt={}, topic={}, partition={}, offset={}, key={}",
                record.topic() + DLT_SUFFIX, record.topic(), record.partition(), record.offset(), record.key(), ex);
    }

    // DLT는 이벤트 토픽과 같은 파티션 수로 처음 보낼 때 만든다
    private void ensureTopic(KafkaAdmin kafkaAdmin, String topic, int partitions) {
        if (readyTopics.contains(topic)) return;
        kafkaAdmin.createOrModifyTopics(TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(TOPIC_REPLICAS)
                .build());
        readyTopics.add(topic);
    }
}
