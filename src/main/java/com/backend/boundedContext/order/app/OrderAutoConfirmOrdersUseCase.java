package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 결제 완료 후 7일 자동 구매 확정
 */
@Component
@RequiredArgsConstructor
public class OrderAutoConfirmOrdersUseCase {

    private final OrderRepository orderRepository;
    private final OrderPolicy orderPolicy;
    private final OrderConfirmSupport orderConfirmSupport;

    // 결제 완료 후 autoConfirmDays일이 지난 PAID 주문 id (afterId 다음부터 최대 limit건, id 순)
    public List<Long> findTargetIds(LocalDateTime now, Long afterId, int limit) {
        LocalDateTime paidBefore = now.minusDays(orderPolicy.getAutoConfirmDays());
        return orderRepository.findAutoConfirmTargetIds(OrderStatus.PAID, paidBefore, afterId, PageRequest.of(0, limit));
    }

    /**
     * 주문 한 건 자동 확정
     * 조회 이후 반품 요청·수동 확정이 먼저 반영됐을 수 있어, 잠근 뒤 대상인지 다시 확인한다
     */
    public boolean autoConfirm(Long orderId, LocalDateTime now) {
        Optional<Order> found = orderRepository.findByIdForUpdate(orderId);
        if (found.isEmpty() || !found.get().isAutoConfirmable(now, orderPolicy.getAutoConfirmDays())) {
            return false;
        }

        orderConfirmSupport.confirm(found.get(), ConfirmType.AUTO, now);
        return true;
    }
}
