package com.backend.boundedContext.settlement.domain;

/** 처리 결과(settlement_process_history.result) */
public enum SettlementHistoryResult {
    /** 성공 */
    SUCCEEDED,
    /** 실패 (재시도 대상) */
    FAILED,
    /** 실패 (재시도 불가 또는 3회 초과) */
    MANUAL_REQUIRED,
    /** 지급 요청 결과 모름 (응답 없음, 5xx). 정산서는 PROCESSING으로 남고 재확인 스케줄러가 확정한다 */
    PROCESSING,
    /** 건너뜀 (지급 가능한 상태가 아님, 이미 처리됨 등) */
    SKIPPED
}