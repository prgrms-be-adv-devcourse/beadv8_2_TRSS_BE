package com.backend.boundedContext.payment.in;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.global.eventConsumer.ProcessedEvents;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.event.DomainEvent;
import com.backend.shared.event.EventEnvelope;
import com.backend.shared.member.event.MemberSignedUpEvent;

import lombok.RequiredArgsConstructor;

/**
 * 회원 컨텍스트의 가입 이벤트를 Kafka(member.events)로 받아 예치금 지갑을 만든다 (TRSS-133 5-2 단계).
 *
 * 흐름: 회원가입 → outbox 저장 → 릴레이가 Kafka 발행 → 이 컨슈머(groupId = payment)
 * 한 메시지를 하나의 트랜잭션에서 처리한다(@Transactional).
 *   1. member.events에 함께 오는 다른 회원 이벤트는 무시한다. eventType 헤더로 먼저 거르고(payload를 읽지 않음),
 *      헤더가 없는 메시지(콘솔로 다시 보낸 DLT 메시지 등)는 봉투를 읽어 거른다
 *   2. 같은 트랜잭션 안에서 eventId를 processed_event에 기록한다. 이미 있으면(재전달) 아무것도 하지 않는다
 *   3. 지갑을 만든다
 * 3이 실패하면 2의 기록도 함께 롤백되므로, 같은 메시지를 다시 받으면 처음부터 다시 처리한다.
 * 실패한 메시지는 1·2·4·8초 간격으로 다시 받고(총 5번), 그래도 실패하면 member.events-dlt로 간다(KafkaConsumerConfig).
 * 지갑 생성은 이미 지갑이 있으면 건너뛰고 wallet.member_id 유니크로 한 번 더 막는다(이중 안전망).
 */
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    /** processed_event.consumer 값이자 Kafka groupId. 같은 이벤트를 다른 컨텍스트와 따로 처리한다 */
    static final String CONSUMER = "payment";

    /** OutboxKafkaSender가 붙이는 이벤트 타입 헤더 */
    private static final String EVENT_TYPE_HEADER = "eventType";

    private final PaymentFacade paymentFacade;
    private final EventMessages eventMessages;
    private final ProcessedEvents processedEvents;

    @KafkaListener(topics = "member.events", groupId = CONSUMER)
    @Transactional
    public void handle(ConsumerRecord<String, String> record) {
        // 다루지 않는 이벤트는 payload를 읽기 전에 거른다. 이 앱이 모르는 새 이벤트 타입이어도 DLT로 보내지 않는다
        Header eventType = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (eventType != null && !MemberSignedUpEvent.class.getSimpleName().equals(
                                    new String(eventType.value(), StandardCharsets.UTF_8))) return;

        EventEnvelope<DomainEvent> envelope = eventMessages.read(record.value());
        if (!(envelope.payload() instanceof MemberSignedUpEvent event)) return;           // 헤더 없이 온 다른 회원 이벤트는 무시
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) return;         // 이미 처리한 이벤트
        paymentFacade.createWallet(event.memberId());
    }
}
