package com.backend.delivery.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.backend.global.outbox.EventMessages;
import com.backend.global.outbox.OutboxPolicy;

/**
 * delivery가 발행하는 토픽(delivery.events)을 기동할 때 만든다.
 * 릴레이는 첫 발행 때 토픽을 만들지만, 그 전에 market 컨슈머(PR 4)가 구독하면 토픽이 없어 기다리게 되므로 미리 만든다.
 */
@Configuration
public class DeliveryKafkaTopicConfig {

    // 단일 브로커
    private static final int TOPIC_REPLICAS = 1;

    // DeliveryCompletedEvent.aggregateType()
    static final String DELIVERY_AGGREGATE_TYPE = "DELIVERY";

    @Bean
    public NewTopic deliveryEventsTopic(OutboxPolicy outboxPolicy) {
        return TopicBuilder.name(EventMessages.topicOf(DELIVERY_AGGREGATE_TYPE))
                .partitions(outboxPolicy.getTopicPartitions())
                .replicas(TOPIC_REPLICAS)
                .build();
    }
}
