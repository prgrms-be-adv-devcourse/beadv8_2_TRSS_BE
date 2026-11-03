package com.backend.shared.order.dto;

import java.time.LocalDateTime;

// 주문 요약
public record OrderSummaryDto(Long orderId, OrderStatus status, long totalAmount, LocalDateTime orderedAt) {
}
