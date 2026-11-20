package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.out.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 배송 완료 반영 (delivery 서비스의 DeliveryCompletedEvent)
 * 주문 행을 잠가서(findByIdForUpdate) 기록하므로 자동 확정·수동 확정과 동시에 와도 차례로 처리된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderMarkDeliveredUseCase {

    private final OrderRepository orderRepository;

    public void markDelivered(Long orderId, LocalDateTime deliveredAt) {
        Optional<Order> found = orderRepository.findByIdForUpdate(orderId);
        if (found.isEmpty()) {
            // 테스트 데이터·삭제된 주문. 다시 처리해도 결과가 같으므로 DLT로 보내지 않고 넘긴다
            log.warn("배송 완료 이벤트의 주문이 없습니다: orderId={}", orderId);
            return;
        }

        Order order = found.get();
        order.markDelivered(deliveredAt);   // 이미 기록돼 있으면 처음 값 유지
        log.info("배송 완료 반영: orderId={}, deliveredAt={}", orderId, order.getDeliveredAt());
    }
}
