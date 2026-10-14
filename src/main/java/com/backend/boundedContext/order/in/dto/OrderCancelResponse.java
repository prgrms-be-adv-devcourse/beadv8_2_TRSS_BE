package com.backend.boundedContext.order.in.dto;

import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;

public record OrderCancelResponse(
        Long orderId,
        String orderNumber,
        OrderStatus status,
        LocalDateTime canceledAt
) { }