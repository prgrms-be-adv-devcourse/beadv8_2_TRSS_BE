package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;

/**
 * 허용하지 않은 결제 수단 취소 결과 확인 대상 (PG 조회와 재시도에 필요한 값만)
 * - payment: PG 조회에 쓰는 충전 건 정보
 * - amount: 충전 금액. PG 취소 합계가 이 금액이면 전액 취소된 것
 * - attempt: 지금 취소 회차. 재시도·확정할 때 같은 회차가 아직 진행 중인지 확인함
 * - updatedAt: 다음 배치를 이어서 가져올 위치
 */
public record TopupMethodCancelTarget(
        Long topupId,
        PgPaymentRef payment,
        PgProvider pgProvider,
        long amount,
        int attempt,
        LocalDateTime updatedAt
) implements TopupStaleTarget {}
