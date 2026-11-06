package com.backend.shared.delivery.dto;

import java.time.LocalDateTime;

// 주문 상세에 붙이는 배송 정보. carrierName은 택배사 표시 이름(예: "CJ대한통운")
// READY면 carrierName·trackingNumber·shippedAt은 null, DELIVERED가 아니면 deliveredAt은 null
public record DeliveryDto(Long deliveryId, DeliveryStatus status, String carrierName, String trackingNumber,
                          LocalDateTime shippedAt, LocalDateTime deliveredAt) { }
