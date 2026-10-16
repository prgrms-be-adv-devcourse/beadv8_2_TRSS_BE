package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.order.dto.OrderCancelType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 상품 판매 중지 및 삭제로 인한 결제 대기 주문 일괄 취소
 */
@Component
@RequiredArgsConstructor
public class OrderCancelPendingOrdersUseCase {

    private static final String PRODUCT_STOPPED_REASON = "상품 판매 중지";

    private final OrderRepository orderRepository;
    private final OrderCancelSupport orderCancelSupport;

    public int cancel(Collection<Long> productIds, OrderCancelType cancelType, LocalDateTime now) {
        if (productIds == null || productIds.isEmpty()) {
            return 0;
        }

        List<Long> orderIds = orderRepository.findIdsByProductIdsAndStatusIn(productIds, Order.PAYMENT_PENDING_STATUSES);
        if (orderIds.isEmpty()) {
            return 0;
        }

        // id 순서대로 잠근다
        List<Order> orders = orderRepository.findAllByIdInForUpdate(orderIds);

        int canceled = 0;
        for (Order order : orders) {
            // 조회 이후 결제 완료,구매자 취소 및 만료가 먼저 반영됐을 수 있어, 잠근 뒤 다시 확인한다
            if (!order.isPaymentPending()) {
                continue;
            }
            orderCancelSupport.cancel(order, cancelType, reasonOf(cancelType), now);
            canceled++;
        }
        return canceled;
    }

    private String reasonOf(OrderCancelType cancelType) {
        return cancelType == OrderCancelType.PRODUCT_STOPPED ? PRODUCT_STOPPED_REASON : cancelType.name();
    }
}
