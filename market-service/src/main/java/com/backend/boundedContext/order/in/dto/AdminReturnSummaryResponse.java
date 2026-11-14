package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 반품 목록의 주문 한 건. 배송지·연락처는 넣지 않는다.
 *
 * @param payAmount   결제 금액(배송비 포함). 승인하면 이 금액 전액을 환불한다
 * @param deliveredAt 배송 완료 시각 (배송지 없는 주문은 null)
 */
public record AdminReturnSummaryResponse(
        Long orderId,
        String orderNumber,
        Long buyerId,
        OrderStatus status,
        long payAmount,
        long shippingFee,
        LocalDateTime deliveredAt,
        LocalDateTime returnRequestedAt,
        ReturnReasonCode reasonCode,
        String reason,
        LocalDateTime returnedAt,
        LocalDateTime returnRejectedAt,
        List<Item> items
) {
    /**
     * @param restoreStock 승인 때 정한 재고 복원 여부 (승인 전이면 null)
     */
    public record Item(
            Long orderItemId,
            Long productId,
            String productName,
            String optionName,
            String sellerName,
            int quantity,
            long lineAmount,
            Boolean restoreStock
    ) {
        static Item from(OrderItem item) {
            return new Item(item.getId(), item.getProductId(), item.getProductName(), item.getOptionName(),
                    item.getSellerName(), item.getQuantity(), item.getLineAmount(), item.getRestoreStock());
        }
    }

    public static AdminReturnSummaryResponse from(Order order) {
        return new AdminReturnSummaryResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getBuyerId(),
                order.getStatus(),
                order.getPayAmount(),
                order.getShippingFee(),
                order.getDeliveredAt(),
                order.getReturnRequestedAt(),
                order.getReturnReasonCode(),
                order.getReturnReason(),
                order.getReturnedAt(),
                order.getReturnRejectedAt(),
                order.getItems().stream().map(Item::from).toList()
        );
    }
}
