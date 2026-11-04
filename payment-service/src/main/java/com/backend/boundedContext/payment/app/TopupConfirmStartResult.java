package com.backend.boundedContext.payment.app;

/**
 * 충전 승인 시작(Tx1) 결과
 * - STARTED: PROCESSING으로 선점함 → 토스 승인 호출로 진행
 * - AMOUNT_MISMATCH: FAILED_AMOUNT_MISMATCH로 기록하고 커밋함 → 호출한 쪽에서 400 AMOUNT_MISMATCH를 던져야 함
 */
public record TopupConfirmStartResult(
        Long topupId,
        Outcome outcome
) {
    public enum Outcome { STARTED, AMOUNT_MISMATCH }

    public boolean isStarted() {
        return outcome == Outcome.STARTED;
    }
}
