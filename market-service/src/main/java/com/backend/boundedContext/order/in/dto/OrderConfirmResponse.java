package com.backend.boundedContext.order.in.dto;

import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;

// 구매확정 응답 data
public record OrderConfirmResponse(
        Long orderId,
        String orderNumber,
        OrderStatus status,
        ConfirmType confirmType,
        LocalDateTime confirmedAt
) { }
