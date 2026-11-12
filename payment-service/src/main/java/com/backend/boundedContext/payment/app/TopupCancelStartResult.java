package com.backend.boundedContext.payment.app;

import com.backend.boundedContext.payment.domain.PgProvider;

/**
 * 충전 취소 시작(Tx1) 결과. PG 취소 호출은 트랜잭션 밖에서 하므로 엔티티 대신 호출에 필요한 값만 넘김
 * - cancelAmount: 이번에 PG에 취소할 금액 (Tx1에서 지갑 잔액에서 이미 뺀 금액)
 * - attempt: 취소 회차. 결과를 반영할 때 같은 회차인지 확인함
 * - idempotencyKey: PG 취소 요청의 멱등 키 (원장 CHARGE_CANCEL과 같은 키)
 */
public record TopupCancelStartResult(
        Long topupId,
        String orderId,
        PgProvider pgProvider,
        String paymentKey,
        long cancelAmount,
        int attempt,
        String idempotencyKey
) {}
