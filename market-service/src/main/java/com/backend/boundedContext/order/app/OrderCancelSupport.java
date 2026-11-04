package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.product.dto.StockRestoreReason;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 결제 전 주문 취소 공통 처리
 * 구매자 취소, 15분 만료, 상품 판매 중지는 모두 이 메서드로 취소한다.
 */
@Component
@RequiredArgsConstructor
public class OrderCancelSupport {

    private final ProductApi productApi;

    // 상품부터 먼저 잠금한다.
    public void lockProducts(Collection<Long> productIds) {
        productApi.lockProductsForOrderChange(productIds);
    }

    public void cancel(Order order, OrderCancelType cancelType, String reason, LocalDateTime now) {
        order.cancel(cancelType, reason, now);   // 결제 대기가 아니면 409-ORDER_NOT_CANCELABLE, 재고 복원 안 함

        productApi.restoreStock(
                order.getItems().stream()
                        .map(OrderItem::toStockChangeDto)
                        .toList(),
                StockRestoreReason.ORDER_RESTORE
        );
    }
}
