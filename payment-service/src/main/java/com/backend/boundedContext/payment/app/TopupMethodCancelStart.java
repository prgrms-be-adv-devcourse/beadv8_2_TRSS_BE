package com.backend.boundedContext.payment.app;

import com.backend.boundedContext.payment.domain.PgProvider;

/**
 * 허용하지 않은 결제 수단 전액 취소 시작 결과. PG 취소 호출은 트랜잭션 밖에서 하므로 엔티티 대신 호출에 필요한 값만 넘김
 * - attempt: 취소 회차. 결과를 반영할 때 같은 회차가 아직 진행 중인지 확인함
 * - idempotencyKey: PG 취소 요청의 멱등 키 (TOPUP-{id}-METHOD-CANCEL-{회차})
 */
public record TopupMethodCancelStart(
        Long topupId,
        PgProvider pgProvider,
        String paymentKey,
        long amount,
        int attempt,
        String idempotencyKey
) {}
