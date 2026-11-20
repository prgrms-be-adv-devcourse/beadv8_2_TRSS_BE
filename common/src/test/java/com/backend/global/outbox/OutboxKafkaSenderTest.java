package com.backend.global.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import com.backend.shared.event.EventEnvelope;
import com.backend.shared.event.OutboxTestEvent;

import tools.jackson.databind.json.JsonMapper;

class OutboxKafkaSenderTest {

    // 아무것도 듣고 있지 않은 주소
    private static final String NO_BROKER = "localhost:1";

    @Test
    @DisplayName("브로커에 닿지 못하면 KafkaUnavailableException (릴레이가 재시도 횟수를 쓰지 않게)")
    void brokerUnavailable() {
        KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, NO_BROKER,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, NO_BROKER,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 500,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 500));
        kafkaAdmin.setOperationTimeout(1);
        OutboxKafkaSender sender = new OutboxKafkaSender(kafkaTemplate, kafkaAdmin,
                new EventMessages(JsonMapper.builder().build(), new OutboxEventTypes()),
                new OutboxPolicy(100, 5, 1_000, 3));

        OutboxEvent outbox = OutboxEvent.create(EventEnvelope.wrap(new OutboxTestEvent(1L)), "{\"testId\":1}");

        assertThatThrownBy(() -> sender.send(outbox)).isInstanceOf(KafkaUnavailableException.class);
    }
}
