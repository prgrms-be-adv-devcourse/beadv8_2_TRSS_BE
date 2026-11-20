package com.backend.boundedContext.payment.out.pg;

/**
 * PG 결제 취소 요청. PG마다 취소에 필요한 값이 달라 함께 넘김
 * - payment: 취소할 결제 (토스는 결제키, 헥토는 주문번호·거래번호로 찾음)
 * - cancelAmount: 이번에 취소할 금액 (부분 취소)
 * - reason: PG에 남기는 취소 사유 (헥토는 받지 않음)
 * - attempt: 취소 회차. 헥토는 이 값을 취소 회차(cnclOrd)로 보냄
 * - idempotencyKey: 토스 Idempotency-Key. 헥토는 멱등 키가 없어 쓰지 않음
 */
public record PgCancelRequest(
        PgPaymentRef payment,
        long cancelAmount,
        String reason,
        int attempt,
        String idempotencyKey
) {}
