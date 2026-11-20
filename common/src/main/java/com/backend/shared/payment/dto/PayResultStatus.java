package com.backend.shared.payment.dto;

public enum PayResultStatus {
    PAID,
    FAILED_INSUFFICIENT_BALANCE,
    FAILED_ORDER_EXPIRED
}
