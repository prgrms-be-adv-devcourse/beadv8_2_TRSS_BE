package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;

/**
 * 취소 결과 확인 대상 충전 건 (PG 조회와 판단에 필요한 값만). 결제한 PG에 조회함
 * - payment: PG 조회에 쓰는 충전 건 정보
 * - attempt: 취소 회차. 반영할 때 같은 회차가 아직 CANCELING인지 확인함
 * - canceledAmount: 이번 회차 전까지 PG 취소가 확정된 금액
 * - cancelingAmount: 이번 회차에 취소를 요청한 금액
 * - updatedAt: 다음 배치를 이어서 가져올 위치
 */
public record TopupCancelReconcileTarget(
        Long topupId,
        PgPaymentRef payment,
        PgProvider pgProvider,
        int attempt,
        long canceledAmount,
        long cancelingAmount,
        LocalDateTime updatedAt
) implements TopupStaleTarget {}
