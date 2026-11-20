package com.backend.shared.delivery.dto;

// 배송상태 (출고 대기 → 배송 중 → 배송 완료)
public enum DeliveryStatus {
    READY,
    SHIPPED,
    DELIVERED
}
