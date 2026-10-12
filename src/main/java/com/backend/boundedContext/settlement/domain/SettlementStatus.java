package com.backend.boundedContext.settlement.domain;

/**
 * 정산서(settlement.status) 상태
 */
public enum SettlementStatus {
    /** 월별 집계 완료, 지급 대기 (HOLD 해제 후에도 이 상태가 된다) */
    READY,
    /** 지급 완료. 다시 지급하지 않음 */
    PAID,
    /** 지급 실패, 다음 재시도 일시 이후 자동 재시도 (총 3회 미만) */
    FAILED,
    /** 총 3회 실패 또는 재시도 불가 사유
     * → 관리자 수동 재처리
     * TODO 파이널 프로젝트 때 자동화 변경 예정
     * */
    MANUAL_REQUIRED,
    /** 지급 보류. HOLD 동안에는 지급하지 않는다 */
    HOLD
}