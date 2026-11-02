package com.backend.global.outbox;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * outbox 행 하나를 Kafka에 발행하고, 브로커가 기록을 마칠 때(ack)까지 기다린다.
 * 토픽이 없으면 처음 보낼 때 만든다
 */
@Component
@RequiredArgsConstructor
public class OutboxKafkaSender {

    // 브로커가 1대라 복제 수는 1
    private static final short TOPIC_REPLICAS = 1;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaAdmin kafkaAdmin;
    private final EventMessages eventMessages;
    private final OutboxPolicy outboxPolicy;

    private final Set<String> readyTopics = ConcurrentHashMap.newKeySet();

    /**
     * @throws KafkaUnavailableException 브로커에 닿지 못한 경우(다음 실행에서 다시 보낸다)
     * @throws IllegalStateException     브로커가 이 메시지를 거부한 경우(재시도 횟수를 센다)
     */
    public void send(OutboxEvent outbox) {
        String topic = EventMessages.topicOf(outbox.getAggregateType());
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, String.valueOf(outbox.getAggregateId()), eventMessages.toJson(outbox));
        record.headers()
                .add("eventId", outbox.getEventId().toString().getBytes(StandardCharsets.UTF_8))
                .add("eventType", outbox.getEventType().getBytes(StandardCharsets.UTF_8));

        try {
            ensureTopic(topic);
            kafkaTemplate.send(record).get(outboxPolicy.getSendTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaUnavailableException(e);
        } catch (TimeoutException e) {
            throw new KafkaUnavailableException(e);
        } catch (ExecutionException e) {
            throw classify(e.getCause());
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    private void ensureTopic(String topic) {
        if (readyTopics.contains(topic)) return;
        kafkaAdmin.createOrModifyTopics(TopicBuilder.name(topic)
                .partitions(outboxPolicy.getTopicPartitions())
                .replicas(TOPIC_REPLICAS)
                .build());
        readyTopics.add(topic);
    }

    // 원인 중에 재시도하면 될 수 있는 오류(시간 초과, 연결 끊김 등)가 있으면 브로커 문제로 본다
    private static RuntimeException classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof RetriableException || t instanceof TimeoutException) {
                return new KafkaUnavailableException(failure);
            }
        }
        return new IllegalStateException("Kafka가 이벤트를 거부했습니다: " + failure.getMessage(), failure);
    }
}
