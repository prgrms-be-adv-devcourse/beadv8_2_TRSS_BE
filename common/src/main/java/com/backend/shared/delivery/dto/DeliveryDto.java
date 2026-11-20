package com.backend.shared.delivery.dto;

import java.time.LocalDateTime;

// 주문 상세에 붙이는 배송 정보
public record DeliveryDto(Long deliveryId, DeliveryStatus status, String carrierName, String trackingNumber,
                          LocalDateTime shippedAt, LocalDateTime deliveredAt) { }
