package com.backend.boundedContext.delivery.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.shared.order.event.OrderPaidEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 완료 이벤트로 출고 대기(READY) 배송 1건을 만든다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryCreateUseCase {

    private final DeliveryRepository deliveryRepository;

    /**
     * 같은 주문의 배송이 이미 있으면 아무것도 하지 않는다(eventId가 다른 중복 이벤트).
     *
     * @throws IllegalArgumentException 배송지 없음·항목 없음·수량 0 이하 등 처리할 수 없는 이벤트 (재시도 없이 DLT)
     */
    public void create(OrderPaidEvent event) {
        if (event.orderId() != null && deliveryRepository.existsByOrderId(event.orderId())) {
            log.info("배송 생성 건너뜀(이미 있음): orderId={}", event.orderId());
            return;
        }
        Delivery delivery = deliveryRepository.save(Delivery.create(event));
        log.info("배송 생성: deliveryId={}, orderId={}, items={}",
                delivery.getId(), delivery.getOrderId(), delivery.getItems().size());
    }
}
