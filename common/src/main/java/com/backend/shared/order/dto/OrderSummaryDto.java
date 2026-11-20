package com.backend.shared.order.dto;

import java.time.LocalDateTime;

// 주문 요약. totalAmount는 결제 금액(쿠폰 할인 후)
public record OrderSummaryDto(Long orderId, OrderStatus status, long totalAmount, LocalDateTime orderedAt) {
}
