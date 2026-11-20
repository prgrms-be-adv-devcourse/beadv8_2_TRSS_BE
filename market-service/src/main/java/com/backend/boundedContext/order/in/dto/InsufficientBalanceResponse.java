package com.backend.boundedContext.order.in.dto;

/**
 * 402-INSUFFICIENT_BALANCE 응답의 data
 *
 * @param shortage               부족한 금액
 * @param recommendedTopupAmount 충전 권장 금액
 * @param remainingSeconds       주문 결제 가능 남은 시간(초)
 */
public record InsufficientBalanceResponse(
        long shortage,
        long recommendedTopupAmount,
        long remainingSeconds
) {
}