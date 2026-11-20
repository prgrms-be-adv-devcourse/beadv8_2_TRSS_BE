package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.shared.order.dto.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 판매자 주문 항목 한 줄
 *
 * <p>상품 쿠폰은 판매자가 부담한다. 그래서 정산은 할인 후 금액({@code discountedLineAmount}) 기준이고,
 * 정산 금액이 {@code lineAmount}보다 적을 수 있다. 장바구니 쿠폰은 플랫폼 부담이라 여기 할인액에 들어가지 않는다.
 *
 * @param orderedAt            주문일
 * @param lineAmount           금액 (단가×수량)
 * @param discountAmount       상품 쿠폰 할인액 (판매자 부담). 상품 쿠폰을 쓰지 않은 항목은 0
 * @param discountedLineAmount 상품 쿠폰 할인 후 금액 = lineAmount − discountAmount. 정산은 이 금액 기준
 * @param status               주문 상태
 */
public record SellerOrderItemResponse(
        Long orderItemId,
        Long orderId,
        String orderNumber,
        LocalDateTime orderedAt,
        Long productId,
        String productName,
        String optionName,
        int quantity,
        long unitPrice,
        long lineAmount,
        @Schema(description = "상품 쿠폰 할인액(원). 상품 쿠폰은 판매자 부담. 쓰지 않은 항목은 0")
        long discountAmount,
        @Schema(description = "상품 쿠폰 할인 후 금액(원) = lineAmount − discountAmount. 정산은 이 금액 기준")
        long discountedLineAmount,
        OrderStatus status
) {
    public static SellerOrderItemResponse from(OrderItem item) {
        Order order = item.getOrder();
        return new SellerOrderItemResponse(
                item.getId(),
                order.getId(),
                order.getOrderNumber(),
                order.getCreatedAt(),
                item.getProductId(),
                item.getProductName(),
                item.getOptionName(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getLineAmount(),
                item.getDiscountAmount(),
                item.getDiscountedLineAmount(),
                order.getStatus()
        );
    }
}
