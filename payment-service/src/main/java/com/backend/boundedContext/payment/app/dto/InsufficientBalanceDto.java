package com.backend.boundedContext.payment.app.dto;

/**
 * 402 INSUFFICIENT_BALANCE 응답의 data. 주문 결제의 잔액 부족 응답과 같은 칸을 쓴다
 *
 * @param shortage     모자란 금액
 * @param chargeAmount 충전 권장액 (모자란 금액을 충전 단위로 올린 금액)
 */
public record InsufficientBalanceDto(long shortage, long chargeAmount) {
}
