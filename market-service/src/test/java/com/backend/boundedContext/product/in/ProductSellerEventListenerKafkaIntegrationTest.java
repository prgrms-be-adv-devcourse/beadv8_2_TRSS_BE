package com.backend.boundedContext.product.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.global.outbox.EventMessages;
import com.backend.global.outbox.OutboxEvent;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.SellerSanctionedEvent;
import com.backend.shared.member.event.SellerWithdrawalRejectedEvent;
import com.backend.shared.member.event.SellerWithdrawalRequestedEvent;

import tools.jackson.databind.ObjectMapper;

/**
 * 회원 서비스가 seller.events로 보낸 판매자 이벤트를 market 컨슈머가 받아 상품을 중지·재개하는지 확인한다.
 * Kafka는 EmbeddedKafka(테스트 JVM 안의 브로커), DB는 로컬 Docker PostgreSQL을 쓴다.
 * 회원 서비스의 outbox 대신, 릴레이(OutboxKafkaSender)와 같은 형식(봉투 JSON + eventType 헤더, 키 sellerId)으로 직접 보낸다.
 * 상품 중지 뒤 결제 대기 주문 취소·재고 복원은 SellerProductStopFlowIntegrationTest가 확인한다.
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
// 컨슈머가 구독하기 전에 토픽을 운영과 같은 파티션 수(3)로 만들어 둔다
@EmbeddedKafka(topics = "seller.events", partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class ProductSellerEventListenerKafkaIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EventMessages eventMessages;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductOptionRepository productOptionRepository;

    // 다른 테스트·시드와 겹치지 않는 판매자 ID
    private final long sellerId = ThreadLocalRandom.current().nextLong(3_000_000_000L, 4_000_000_000L);

    /** 테스트가 보낸 이벤트 ID(처리 기록 정리용) */
    private final List<UUID> eventIds = new ArrayList<>();

    private Long productId;
    private Long optionId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            Product product = productRepository.save(Product.create(
                    sellerId, categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(), "판매자 이벤트 테스트 크림", "설명", 10_000L));
            productId = product.getId();
            optionId = productOptionRepository.save(ProductOption.createDefault(product, 10)).getId();
        });
    }

    @AfterEach
    void cleanUp() {
        transactionTemplate.executeWithoutResult(status -> {
            productOptionRepository.deleteById(optionId);
            productRepository.deleteById(productId);
        });
        eventIds.forEach(id -> jdbcTemplate.update("delete from processed_event where event_id = ?", id));
    }

    @Test
    @DisplayName("제재 이벤트를 받으면 판매자의 판매 중 상품이 중지된다")
    void sanctioned_stopsProducts() {
        send(new SellerSanctionedEvent(sellerId, "위조 상품 판매"));

        await().atMost(WAIT).until(() -> status() == ProductSaleStatus.STOPPED);
        assertThat(productRepository.findById(productId).orElseThrow().getStoppedReason()).isEqualTo("위조 상품 판매");
    }

    @Test
    @DisplayName("철회 요청 뒤 철회 거절 이벤트가 같은 순서로 오면, 상품이 중지됐다가 다시 판매 중이 된다")
    void withdrawalRequestedThenRejected_resumesProducts() {
        UUID requested = send(new SellerWithdrawalRequestedEvent(sellerId, "폐업 예정"));
        UUID rejected = send(new SellerWithdrawalRejectedEvent(sellerId));

        // 같은 키(sellerId)라 같은 파티션에서 보낸 순서대로 처리한다. 둘 다 처리된 뒤 최종 상태를 본다
        await().atMost(WAIT).until(() -> processed(requested) && processed(rejected));
        assertThat(status()).isEqualTo(ProductSaleStatus.ON_SALE);
    }

    @Test
    @DisplayName("같은 이벤트가 다시 와도 한 번만 처리한다")
    void duplicateMessage_handledOnce() {
        SellerSanctionedEvent event = new SellerSanctionedEvent(sellerId, "위조 상품 판매");
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event);
        send(envelope);
        send(envelope);
        // 같은 키로 뒤에 보낸 이벤트가 처리되면 앞의 중복 메시지도 이미 받은 것이다
        UUID marker = send(new SellerWithdrawalRejectedEvent(sellerId));

        await().atMost(WAIT).until(() -> processed(marker));
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from processed_event where event_id = ? and consumer = 'market'", Integer.class, envelope.eventId());
        assertThat(count).isEqualTo(1);
    }

    private UUID send(DomainEvent event) {
        return send(EventEnvelope.wrap(event));
    }

    // 릴레이(OutboxKafkaSender)와 같은 형식으로 보낸다
    private UUID send(EventEnvelope<DomainEvent> envelope) {
        OutboxEvent outbox = OutboxEvent.create(envelope, objectMapper.writeValueAsString(envelope.payload()));
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "seller.events", String.valueOf(sellerId), eventMessages.toJson(outbox));
        record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
        eventIds.add(envelope.eventId());
        return envelope.eventId();
    }

    private boolean processed(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from processed_event where event_id = ? and consumer = 'market'", Integer.class, eventId);
        return count != null && count == 1;
    }

    private ProductSaleStatus status() {
        return productRepository.findById(productId).orElseThrow().getStatus();
    }
}
