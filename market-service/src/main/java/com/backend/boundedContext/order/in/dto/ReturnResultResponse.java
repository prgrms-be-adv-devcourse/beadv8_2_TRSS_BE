package com.backend.boundedContext.order.in.dto;

import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.OrderStatus;

/**
 * 반품 승인·거절 결과
 *
 * @param status      승인: RETURNED / 거절: PAID(자동 확정 전) 또는 CONFIRMED(기준일이 지나 즉시 확정)
 * @param confirmType 거절하면서 즉시 구매 확정했으면 RETURN_REJECTED, 아니면 null
 */
public record ReturnResultResponse(
        Long orderId,
        OrderStatus status,
        ConfirmType confirmType
) {
}
