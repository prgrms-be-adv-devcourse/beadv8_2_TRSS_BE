package com.backend.shared.order.dto;

// 주문상태
public enum OrderStatus {
    CREATED,
    PAYMENT_FAILED,
    PAID,
    CONFIRMED,
    CANCELED,
    RETURN_REQUESTED,
    RETURNED
}
