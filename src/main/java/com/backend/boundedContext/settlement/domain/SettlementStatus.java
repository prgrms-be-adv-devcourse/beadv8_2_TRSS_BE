package com.backend.boundedContext.settlement.domain;

/**
 * 정산서(settlement.status) 상태
 */
public enum SettlementStatus {
    /** 월별 집계 완료, 지급 대기 (HOLD 해제 후에도 이 상태가 된다) */
    READY,
    /** 지급 진행 중 (3구간 중 ① 선점 완료) */
    PROCESSING,
    /** 판매자 지갑 입금 성공 */
    PAID,
    /** 지급 실패, next_retry_at 이후 자동 재시도 */
    FAILED,
    /** 총 3회 실패 또는 재시도 불가 사유 → 관리자 수동 재처리 대상 */
    MANUAL_REQUIRED,
    /** 정산 보류. HOLD 동안에는 지급하지 않는다 */
    HOLD
}