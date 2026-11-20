package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;

// 결과 확인 대상 충전 건 (PG 조회에 필요한 값만). 결제한 PG에 조회함
public record TopupReconcileTarget(
        Long topupId,
        PgPaymentRef payment,   // PG 조회에 쓰는 충전 건 정보
        PgProvider pgProvider,
        LocalDateTime updatedAt   // 다음 배치를 이어서 가져올 위치
) implements TopupStaleTarget {}
