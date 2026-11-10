package com.backend.boundedContext.notification.in;

import com.backend.global.outbox.EventMessages;
import com.backend.global.outbox.OutboxEvent;
import com.backend.shared.delivery.event.DeliveryCompletedEvent;
import com.backend.shared.delivery.event.DeliveryShippedEvent;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.PaidOrderItemDto;
import com.backend.shared.order.dto.ShippingAddressDto;
import com.backend.shared.order.event.OrderConfirmedEvent;
import com.backend.shared.order.event.OrderPaidEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 주문(order.events)·배송(delivery.events) 이벤트를 알림 컨슈머가 받아 구매자 알림을 저장하는지 확인함
 * Kafka는 EmbeddedKafka(테스트 JVM 안의 브로커), DB는 로컬 Docker PostgreSQL을 씀.
 * 주문·배송의 outbox 대신, 릴레이(OutboxKafkaSender)와 같은 형식(봉투 JSON + eventType 헤더, 키 aggregateId)으로 직접 보냄.
 */
@SpringBootTest(properties = "spring.kafka.consumer.auto-offset-reset=earliest")
// 컨슈머가 구독하기 전에 토픽을 운영과 같은 파티션 수(3)로 만들어 둠
@EmbeddedKafka(topics = {"order.events", "delivery.events"}, partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class NotificationEventListenerKafkaIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long DELIVERY_ID = 70L;

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EventMessages eventMessages;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 다른 테스트와 겹치지 않는 구매자·주문 ID
    private final long buyerId = ThreadLocalRandom.current().nextLong(6_000_000_000L, 7_000_000_000L);
    private final long orderId = ThreadLocalRandom.current().nextLong(6_000_000_000L, 7_000_000_000L);

    private final List<UUID> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from notification where member_id = ?", buyerId);
        eventIds.forEach(id -> jdbcTemplate.update("delete from processed_event where event_id = ?", id));
    }

    @Test
    @DisplayName("결제 완료 이벤트를 받으면 구매자에게 주문 완료 알림이 저장됨")
    void orderPaid_savesNotification() {
        UUID eventId = send("order.events", paidEvent());

        await().atMost(WAIT).until(() -> processed(eventId));
        assertThat(notifications()).containsExactly("ORDER_PAID|주문이 완료되어 스토어에서 상품 준비 중입니다.");
    }

    @Test
    @DisplayName("배송 시작·완료 이벤트를 받으면 보낸 순서대로 배송 알림이 저장됨")
    void delivery_savesNotifications() {
        send("delivery.events", new DeliveryShippedEvent(DELIVERY_ID, orderId, buyerId, "CJ대한통운", "123456789012",
                LocalDateTime.of(2026, 11, 2, 10, 0)));
        UUID completed = send("delivery.events", new DeliveryCompletedEvent(DELIVERY_ID, orderId, buyerId,
                LocalDateTime.of(2026, 11, 3, 15, 0)));

        // 같은 키(deliveryId)로 뒤에 보낸 배송 완료가 처리되면 앞의 배송 시작도 이미 처리된 것임
        await().atMost(WAIT).until(() -> processed(completed));
        assertThat(notifications()).containsExactly(
                "DELIVERY_STARTED|상품이 발송되었습니다. (CJ대한통운 123456789012)",
                "DELIVERY_COMPLETED|상품이 배송 완료되었습니다.");
    }

    @Test
    @DisplayName("같은 eventId가 두 번 오면 알림을 한 번만 저장함")
    void duplicateMessage_savedOnce() {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(paidEvent());
        send("order.events", envelope);
        send("order.events", envelope);
        // 같은 키(orderId)로 뒤에 보낸 이벤트가 처리되면 앞의 중복 메시지도 이미 받은 것임
        UUID marker = send("order.events", paidEvent());

        await().atMost(WAIT).until(() -> processed(marker));
        assertThat(notifications()).hasSize(2);   // 중복 메시지 1건 + 표시용 이벤트 1건
    }

    @Test
    @DisplayName("같은 토픽의 다른 주문 이벤트(구매 확정)는 무시함: 처리 기록·알림 없음")
    void otherOrderEvent_ignored() {
        UUID confirmed = send("order.events", new OrderConfirmedEvent(orderId, buyerId, LocalDateTime.now(), ConfirmType.MANUAL, List.of()));
        UUID marker = send("order.events", paidEvent());

        await().atMost(WAIT).until(() -> processed(marker));
        assertThat(processed(confirmed)).isFalse();
        assertThat(notifications()).containsExactly("ORDER_PAID|주문이 완료되어 스토어에서 상품 준비 중입니다.");
    }

    private OrderPaidEvent paidEvent() {
        ShippingAddressDto address = new ShippingAddressDto("홍길동", "01012345678", "06236", "서울 강남구 테헤란로 1", null, null);
        PaidOrderItemDto item = new PaidOrderItemDto(1L, 10L, 100L, 2L, "상점", "립스틱", "기본", 1);
        return new OrderPaidEvent(orderId, "ORD-TEST", buyerId, LocalDateTime.now(), address, List.of(item));
    }

    private UUID send(String topic, DomainEvent event) {
        return send(topic, EventEnvelope.wrap(event));
    }

    // 릴레이(OutboxKafkaSender)와 같은 형식으로 보냄. 키는 aggregateId(orderId·deliveryId)
    private UUID send(String topic, EventEnvelope<DomainEvent> envelope) {
        OutboxEvent outbox = OutboxEvent.create(envelope, objectMapper.writeValueAsString(envelope.payload()));
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic, String.valueOf(envelope.payload().aggregateId()), eventMessages.toJson(outbox));
        record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
        eventIds.add(envelope.eventId());
        return envelope.eventId();
    }

    private boolean processed(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from processed_event where event_id = ? and consumer = 'notification'", Integer.class, eventId);
        return count != null && count == 1;
    }

    // 구매자 알림을 저장된 순서대로 "타입|문구"로 꺼냄
    private List<String> notifications() {
        return jdbcTemplate.queryForList(
                "select type || '|' || message from notification where member_id = ? order by id", String.class, buyerId);
    }
}
