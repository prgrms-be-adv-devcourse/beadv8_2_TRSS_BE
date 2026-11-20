package com.backend.delivery.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

import com.backend.boundedContext.delivery.FakeDeliveryTracker;

/**
 * 기동하면 delivery.events 토픽이 운영과 같은 파티션 수(3)로 만들어진다 (PR 4의 market 컨슈머가 토픽을 기다리지 않게)
 * 테스트 기본값은 기동 시 토픽 생성을 꺼 두므로(application.properties) 여기서만 켠다.
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",
        "spring.kafka.admin.auto-create=true"
})
@Import(FakeDeliveryTracker.Config.class)
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class DeliveryKafkaTopicConfigTest {

    @Autowired private EmbeddedKafkaBroker embeddedKafka;

    @Test
    @DisplayName("기동 후 delivery.events 토픽이 파티션 3개로 있다")
    void deliveryEventsTopicCreated() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString()))) {
            assertThat(admin.listTopics().names().get()).contains("delivery.events");
            TopicDescription description = admin.describeTopics(java.util.List.of("delivery.events"))
                    .allTopicNames().get().get("delivery.events");
            assertThat(description.partitions()).hasSize(3);
        }
    }
}
