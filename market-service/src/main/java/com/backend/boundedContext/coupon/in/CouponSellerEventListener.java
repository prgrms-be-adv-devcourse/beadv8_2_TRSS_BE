package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.SellerSanctionedEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * 회원 서비스의 판매자 이벤트를 Kafka(seller.events)로 받아 판매자 제재 때 협약을 모두 종료한다.
 *
 * <p>상품 쪽 컨슈머(ProductSellerEventListener, groupId = market)와 같은 토픽을 받지만 groupId를 따로 둔다(market-coupon).
 * 같은 groupId로 두면 파티션이 두 컨슈머에 나뉘어 한 이벤트를 둘 중 하나만 받게 된다.
 *
 * <ul>
 *     <li>제재(SellerSanctionedEvent): 요청 중이거나 진행 중인 협약을 모두 종료(SYSTEM)하고 그 협약의 상품 쿠폰을 중지한다.</li>
 *     <li>철회 요청, 철회 거절은 처리하지 않는다. 철회 요청으로 상품이 중지되면 상품 쪽에서 그 상품의 쿠폰이 중지되고,
 *         철회가 거절될 수 있어 협약은 그대로 둔다. 중지된 쿠폰은 철회가 거절돼도 되살리지 않는다.</li>
 * </ul>
 * 중복 처리 막기와 재시도는 상품 쪽 컨슈머와 같다: 같은 트랜잭션에서 processed_event에 먼저 기록하고,
 * 실패하면 기록도 함께 롤백돼 다시 받는다.
 */
@Component
@RequiredArgsConstructor
public class CouponSellerEventListener {

    /** processed_event.consumer 값이자 Kafka groupId */
    static final String CONSUMER = "market-coupon";

    /** OutboxKafkaSender가 붙이는 이벤트 타입 헤더 */
    private static final String EVENT_TYPE_HEADER = "eventType";

    private static final String HANDLED_EVENT_TYPE = SellerSanctionedEvent.class.getSimpleName();

    private final CouponFacade couponFacade;
    private final EventMessages eventMessages;
    private final ProcessedEvents processedEvents;

    @KafkaListener(topics = "seller.events", groupId = CONSUMER)
    @Transactional
    public void handle(ConsumerRecord<String, String> record) {
        // 다루지 않는 이벤트는 payload를 읽기 전에 거른다
        Header eventType = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (eventType != null && !HANDLED_EVENT_TYPE.equals(new String(eventType.value(), StandardCharsets.UTF_8))) return;

        EventEnvelope<DomainEvent> envelope = eventMessages.read(record.value());
        if (!HANDLED_EVENT_TYPE.equals(envelope.eventType())) return;                  // 헤더 없이 온 다른 판매자 이벤트는 무시
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) return;     // 이미 처리한 이벤트

        if (!(envelope.payload() instanceof SellerSanctionedEvent event)) {
            throw new IllegalStateException("처리할 수 없는 판매자 이벤트: " + envelope.eventType());
        }
        couponFacade.endPartnershipsBySellerSanction(event.sellerId());
    }
}
