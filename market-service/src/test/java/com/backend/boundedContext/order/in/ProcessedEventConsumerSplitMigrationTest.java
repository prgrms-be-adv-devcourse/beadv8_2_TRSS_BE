package com.backend.boundedContext.order.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.product.in.ProductSellerEventListener;

/**
 * V21: processed_event.consumer를 리스너마다 나누고(market-order-delivery, market-product-seller),
 * 이전 이름(market)으로 남은 처리 기록을 두 새 이름으로 옮긴다. Kafka groupId는 market 그대로 (TRSS-307)
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class ProcessedEventConsumerSplitMigrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("리스너마다 processed_event consumer 이름이 다르고, Kafka groupId는 둘 다 market 그대로다")
    void consumerNamesSplit_groupIdUnchanged() throws Exception {
        assertThat(OrderDeliveryEventListener.CONSUMER).isEqualTo("market-order-delivery");
        assertThat(groupIdOf(OrderDeliveryEventListener.class)).isEqualTo("market");
        assertThat(groupIdOf(ProductSellerEventListener.class)).isEqualTo("market");
        // ProductSellerEventListener.CONSUMER는 다른 패키지의 package-private 상수라 V21 본문으로 확인한다
        assertThat(v21()).contains("'market-order-delivery'", "'market-product-seller'");
    }

    @Test
    @DisplayName("V21 적용 이력이 성공으로 남는다")
    void applied() {
        assertThat(jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '21'", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("이전 이름(market)의 처리 기록을 두 새 이름으로 복사하고 market 행은 남긴다. 다시 실행해도 실패하지 않는다 (롤백)")
    void copiesOldRows() throws Exception {
        UUID oldEvent = UUID.randomUUID();
        UUID alreadyCopied = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("insert into processed_event (event_id, consumer, processed_at) values (?, 'market', now())", oldEvent);
            jdbcTemplate.update("insert into processed_event (event_id, consumer, processed_at) values (?, 'market', now())", alreadyCopied);
            jdbcTemplate.update("insert into processed_event (event_id, consumer, processed_at) values (?, 'market-order-delivery', now())",
                    alreadyCopied);

            String v21;
            try {
                v21 = v21();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            jdbcTemplate.execute(v21);
            jdbcTemplate.execute(v21);   // 두 번 실행해도 on conflict do nothing

            assertThat(consumersOf(oldEvent)).containsExactly("market", "market-order-delivery", "market-product-seller");
            assertThat(consumersOf(alreadyCopied)).containsExactly("market", "market-order-delivery", "market-product-seller");
            status.setRollbackOnly();
        });
    }

    private List<String> consumersOf(UUID eventId) {
        return jdbcTemplate.queryForList(
                "select consumer from processed_event where event_id = ? order by consumer", String.class, eventId);
    }

    private static String v21() throws Exception {
        return new ClassPathResource("db/migration/V21__split_market_processed_event_consumer.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static String groupIdOf(Class<?> listener) throws NoSuchMethodException {
        return listener.getMethod("handle", ConsumerRecord.class).getAnnotation(KafkaListener.class).groupId();
    }
}
