package com.backend.boundedContext.settlement.domain;

/**
 * 정산서(settlement.status) 상태
 */
public enum SettlementStatus {
    /** 월별 집계 완료, 지급 대기 (HOLD 해제 후에도 이 상태가 된다) */
    READY,
    /** 지급 완료. 다시 지급하지 않음 */
    PAID,
    /**
     * 지급 요청 중, 결과 확인 전 (결과 모름)
     * 결제 서비스에 지급을 요청하기 전에 이 상태로 커밋하고, 결과를 받으면 PAID·FAILED·MANUAL_REQUIRED로 바꾼다.
     * 응답이 없거나 5xx면 이 상태로 남고, 재확인 스케줄러가 같은 멱등 키로 결제 원장을 조회(findTransfer)해 확정한다.
     * 지급 가능 상태가 아니므로 관리자 보류, 제재 보류, 재시도 대상에서 자동으로 빠진다
     */
    PROCESSING,
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