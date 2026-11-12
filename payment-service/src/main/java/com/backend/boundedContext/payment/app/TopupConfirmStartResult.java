package com.backend.boundedContext.payment.app;

import com.backend.boundedContext.payment.domain.PgProvider;

/**
 * 충전 승인 시작(Tx1) 결과. 승인을 보낼 PG(충전 건에 기록된 PG)를 함께 넘김
 * - STARTED: PROCESSING으로 선점함 → PG 승인 호출로 진행
 * - AMOUNT_MISMATCH: FAILED_AMOUNT_MISMATCH로 기록하고 커밋함 → 호출한 쪽에서 400 AMOUNT_MISMATCH를 던져야 함
 */
public record TopupConfirmStartResult(
        Long topupId,
        PgProvider pgProvider,
        Outcome outcome
) {
    public enum Outcome { STARTED, AMOUNT_MISMATCH }

    public boolean isStarted() {
        return outcome == Outcome.STARTED;
    }
}
