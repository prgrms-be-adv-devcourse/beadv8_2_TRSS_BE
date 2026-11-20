package com.backend.boundedContext.payment.in;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.order.event.OrderReturnedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 주문 컨텍스트의 반품 승인 이벤트를 Kafka(order.events)로 받아 환불한다.
 *
 * 다른 리스너와 달리 @Transactional을 걸지 않는다: 환불은 ① 요청 기록 → ② 환불 → ③ 실패 기록을 각자 트랜잭션으로 연다(PaymentFacade)
 * processed_event도 쓰지 않는다: 같은 반품은 payment_refund.order_id와 기록 상태로 걸러지고,
 *   이벤트 ID로 건너뛰면 Kafka 재시도로 다시 온 메시지까지 건너뛰어 재시도가 안 된다
 * 일시 오류면 refundReturnedOrder가 예외를 던지고, Kafka가 1·2·4·8초 간격으로 다시 준다(KafkaConsumerConfig). 그래도 실패하면 order.events-dlt
 */
@Component
@RequiredArgsConstructor
public class PaymentOrderEventListener {

    /** OutboxKafkaSender가 붙이는 이벤트 타입 헤더 */
    private static final String EVENT_TYPE_HEADER = "eventType";

    private static final String HANDLED_EVENT_TYPE = OrderReturnedEvent.class.getSimpleName();

    private final PaymentFacade paymentFacade;
    private final EventMessages eventMessages;

    @KafkaListener(topics = "order.events", groupId = PaymentEventListener.CONSUMER)
    public void handle(ConsumerRecord<String, String> record) {
        // order.events에 함께 오는 다른 주문 이벤트(결제 완료·구매 확정 등)는 payload를 읽기 전에 거른다
        Header eventType = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (eventType != null && !HANDLED_EVENT_TYPE.equals(new String(eventType.value(), StandardCharsets.UTF_8))) return;

        EventEnvelope<DomainEvent> envelope = eventMessages.read(record.value());
        if (!(envelope.payload() instanceof OrderReturnedEvent event)) return;   // 헤더 없이 온 다른 주문 이벤트는 무시
        paymentFacade.refundReturnedOrder(event);
    }
}
