package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.global.outbox.EventMessages;
import com.backend.shared.order.event.OrderConfirmedEvent;
import com.backend.shared.order.event.OrderPaidEvent;
import com.backend.shared.order.event.OrderReturnedEvent;
import com.backend.shared.product.event.WishAddedEvent;
import com.backend.shared.product.event.WishRemovedEvent;
import com.backend.shared.review.event.ReviewCreatedEvent;
import com.backend.shared.review.event.ReviewDeletedEvent;
import com.backend.shared.review.event.ReviewModifiedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 회원 추천 다시 계산 컨슈머. 찜 추가·해제, 결제·구매확정·반품, 리뷰 작성·수정·삭제가 오면 그 회원의 추천을 다시 계산해 저장한다.
 * 묶음으로 받아 같은 회원은 한 번만 계산한다. 결과는 회원의 지금 행동으로 다시 계산하므로 중복·순서 바뀜에도 같아
 * processed_event가 필요 없다. 계산이 실패해도 메시지를 다시 처리하지 않고 넘어간다
 * (저장한 결과가 오래되면 읽을 때 다시 계산하므로 다음 기회에 맞춰진다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductRecommendKafkaListener {

    /** Kafka groupId */
    static final String CONSUMER = "market-recommend";

    /** OutboxKafkaSender가 붙이는 이벤트 타입 헤더 */
    private static final String EVENT_TYPE_HEADER = "eventType";
    private static final Set<String> HANDLED_EVENT_TYPES = Set.of(
            WishAddedEvent.class.getSimpleName(),
            WishRemovedEvent.class.getSimpleName(),
            OrderPaidEvent.class.getSimpleName(),
            OrderConfirmedEvent.class.getSimpleName(),
            OrderReturnedEvent.class.getSimpleName(),
            ReviewCreatedEvent.class.getSimpleName(),
            ReviewModifiedEvent.class.getSimpleName(),
            ReviewDeletedEvent.class.getSimpleName());

    private final ProductFacade productFacade;
    private final EventMessages eventMessages;

    // 처음 구독할 때 토픽에 남은 지난 이벤트(최대 보관 기간 7일)를 읽지 않는다. 읽으면 첫 배포 때 그 회원들을 한꺼번에 다시 계산하는데,
    // 추천은 저장한 결과가 없으면 읽을 때 계산하므로 지난 이벤트가 필요 없다. 오프셋을 커밋한 뒤에는 커밋한 곳부터 읽는다
    @KafkaListener(topics = {"product.events", "order.events", "review.events"}, groupId = CONSUMER,
            containerFactory = ProductRecommendKafkaConfig.LISTENER_FACTORY,
            properties = "auto.offset.reset=latest")
    public void handle(List<ConsumerRecord<String, String>> records) {
        Set<Long> memberIds = new LinkedHashSet<>();
        records.forEach(record -> {
            Long memberId = memberId(record);
            if (memberId != null) memberIds.add(memberId);
        });
        for (Long memberId : memberIds) {
            try {
                productFacade.refreshMemberRecommendations(memberId);
            } catch (RuntimeException e) {
                log.warn("회원 추천을 다시 계산하지 못했습니다. 저장한 결과가 오래되면 읽을 때 다시 계산합니다. memberId={}", memberId, e);
            }
        }
    }

    /** 추천을 다시 계산할 회원 ID. 다루지 않는 이벤트, 회원 ID가 없는 예전 리뷰 이벤트, 깨진 메시지는 null */
    Long memberId(ConsumerRecord<String, String> record) {
        try {
            Header header = record.headers().lastHeader(EVENT_TYPE_HEADER);
            if (header != null && !HANDLED_EVENT_TYPES.contains(new String(header.value(), StandardCharsets.UTF_8))) return null;
            return switch (eventMessages.read(record.value()).payload()) {
                case WishAddedEvent event -> event.memberId();
                case WishRemovedEvent event -> event.memberId();
                case OrderPaidEvent event -> event.buyerId();
                case OrderConfirmedEvent event -> event.buyerId();
                case OrderReturnedEvent event -> event.buyerId();
                case ReviewCreatedEvent event -> event.memberId();
                case ReviewModifiedEvent event -> event.memberId();
                case ReviewDeletedEvent event -> event.memberId();
                default -> null;
            };
        } catch (RuntimeException e) {
            log.error("회원 추천 메시지를 읽지 못해 건너뜁니다. topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset(), e);
            return null;
        }
    }
}
