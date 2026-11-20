package com.backend.shared.order.dto;

import java.time.LocalDateTime;

/**
 * 구매자가 결제한 상품 하나 (OrderApi.getRecentPurchases)
 *
 * @param productId 상품 ID
 * @param paidAt    그 상품을 가장 최근에 결제한 시각
 */
public record PurchasedProductDto(Long productId, LocalDateTime paidAt) {
}
