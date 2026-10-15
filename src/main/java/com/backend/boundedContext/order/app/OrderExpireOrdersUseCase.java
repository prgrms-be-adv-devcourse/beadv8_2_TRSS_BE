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
 * 결제 만료 주문 취소
 * 만료 시각 + expireGraceMinutes분이 지난 결제 대기 주문을 한 건씩 취소하고 재고를 복원한다.
 */
@Component
@RequiredArgsConstructor
public class OrderExpireOrdersUseCase {

    private static final String EXPIRED_REASON = "결제 시간 만료";

    private final OrderRepository orderRepository;
    private final OrderPolicy orderPolicy;
    private final OrderCancelSupport orderCancelSupport;

    // 만료 처리 대상 주문 id
    public List<Long> findTargetIds(LocalDateTime now, Long afterId, int limit) {
        LocalDateTime expiredBefore = now.minusMinutes(orderPolicy.getExpireGraceMinutes());
        return orderRepository.findExpireTargetIds(Order.PAYMENT_PENDING_STATUSES, expiredBefore, afterId, PageRequest.of(0, limit));
    }

    /**
     * 주문 한 건 만료 취소
     * 조회 이후 결제 완료·구매자 취소가 먼저 반영됐을 수 있어, 잠근 뒤 대상인지 다시 확인한다
     */
    public boolean expire(Long orderId, LocalDateTime now) {
        Optional<Order> found = orderRepository.findByIdForUpdate(orderId);
        if (found.isEmpty() || !found.get().isExpirable(now, orderPolicy.getExpireGraceMinutes())) {
            return false;
        }

        orderCancelSupport.cancel(found.get(), OrderCancelType.EXPIRED, EXPIRED_REASON, now);
        return true;
    }
}
