package com.backend.boundedContext.payment.app.dto;

/**
 * 결과 확인 한 회차의 처리 결과
 * - completed: 토스에서 승인돼 예치금에 반영
 * - failed: 토스에서 승인되지 않아 실패로 기록
 * - pending: 결과를 아직 알 수 없어 PROCESSING으로 둠 (다음 회차에 다시 확인)
 * - errors: 처리 중 오류 (로그로 남기고 다음 회차에 다시 확인)
 */
public record TopupReconcileResult(
        int completed,
        int failed,
        int pending,
        int errors
) {
    public int total() {
        return completed + failed + pending + errors;
    }
}
