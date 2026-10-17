package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.order.dto.OrderCancelType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 결제 만료 후 주문 취소
 */
@Component
@RequiredArgsConstructor
public class OrderExpireOrdersUseCase {

    private static final String EXPIRED_REASON = "결제 시간 만료";

    private final OrderRepository orderRepository;
    private final OrderPolicy orderPolicy;
    private final OrderCancelSupport orderCancelSupport;

    // 만료 처리 대상 주문 id 조회
    public List<Long> findTargetIds(LocalDateTime now, Long afterId, int limit) {
        LocalDateTime expiredBefore = now.minusMinutes(orderPolicy.getExpireGraceMinutes());
        return orderRepository.findExpireTargetIds(Order.PAYMENT_PENDING_STATUSES, expiredBefore, afterId, PageRequest.of(0, limit));
    }

    // 주문 한 건 만료 취소
    public boolean expire(Long orderId, LocalDateTime now) {
        // 상품 -> 주문 잠금
        List<Long> productIds = orderRepository.findProductIdsByOrderId(orderId);
        if (productIds.isEmpty()) {
            return false;
        }
        orderCancelSupport.lockProducts(productIds);

        Optional<Order> found = orderRepository.findByIdForUpdate(orderId);
        if (found.isEmpty() || !found.get().isExpirable(now, orderPolicy.getExpireGraceMinutes())) {
            return false;
        }

        orderCancelSupport.cancel(found.get(), OrderCancelType.EXPIRED, EXPIRED_REASON, now);
        return true;
    }
}
