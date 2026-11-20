package com.backend.boundedContext.order.in.dto;

import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;

/**
 * 반품 요청 결과
 *
 * @param status            RETURN_REQUESTED
 * @param returnRequestedAt 반품 요청 시각
 */
public record ReturnRequestResponse(
        Long orderId,
        OrderStatus status,
        LocalDateTime returnRequestedAt
) {
}
