package com.backend.boundedContext.settlement.domain;

/** 처리 이력의 대상 유형 (settlement_process_history.target_type) */
public enum SettlementHistoryTargetType {
    /** 정산 후보 */
    CANDIDATE,
    /** 정산서 */
    SETTLEMENT,
    /** 판매자 (판별 단계의 판매자 단위 판정. target_id = 판매자 ID) */
    SELLER
}
