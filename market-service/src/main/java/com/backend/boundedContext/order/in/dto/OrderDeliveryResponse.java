package com.backend.boundedContext.order.in.dto;

import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.dto.DeliveryStatus;

import java.time.LocalDateTime;

/**
 * 주문 상세의 배송 정보 (delivery 서비스에서 조회)
 *
 * @param status         배송 상태 (READY 출고 대기 → SHIPPED 배송 중 → DELIVERED 배송 완료)
 * @param carrierName    택배사 표시 이름. READY면 null
 * @param trackingNumber 송장번호. READY면 null
 * @param shippedAt      출고(송장 등록) 시각. READY면 null
 * @param deliveredAt    배송 완료 시각. DELIVERED가 아니면 null
 */
public record OrderDeliveryResponse(
        DeliveryStatus status,
        String carrierName,
        String trackingNumber,
        LocalDateTime shippedAt,
        LocalDateTime deliveredAt
) {
    public static OrderDeliveryResponse from(DeliveryDto delivery) {
        return new OrderDeliveryResponse(delivery.status(), delivery.carrierName(), delivery.trackingNumber(),
                delivery.shippedAt(), delivery.deliveredAt());
    }
}
