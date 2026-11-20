package com.backend.boundedContext.payment.app;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;

/**
 * 충전 취소 시작(Tx1) 결과. PG 취소 호출은 트랜잭션 밖에서 하므로 엔티티 대신 호출에 필요한 값만 넘김
 * - payment: PG 취소에 쓰는 충전 건 정보
 * - cancelAmount: 이번에 PG에 취소할 금액 (Tx1에서 지갑 잔액에서 이미 뺀 금액)
 * - canceledAmountBefore: 이번 취소 전까지 확정된 취소 금액. PG가 거절했을 때 조회한 취소 합계와 비교함
 * - attempt: 취소 회차. 결과를 반영할 때 같은 회차인지 확인함
 * - idempotencyKey: PG 취소 요청의 멱등 키 (원장 CHARGE_CANCEL과 같은 키)
 */
public record TopupCancelStartResult(
        Long topupId,
        PgProvider pgProvider,
        PgPaymentRef payment,
        long cancelAmount,
        long canceledAmountBefore,
        int attempt,
        String idempotencyKey
) {}
