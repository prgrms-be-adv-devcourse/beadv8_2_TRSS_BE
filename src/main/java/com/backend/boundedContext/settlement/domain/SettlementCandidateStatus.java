package com.backend.boundedContext.settlement.domain;

// 정산 후보(settlement_candidate.status) 상태
public enum SettlementCandidateStatus {
    /** 구매 확정으로 생성, 아직 정산서에 묶이지 않음 */
    READY,
    /** 정산서에 묶임 */
    INCLUDED,
    /** 관리자가 정산 대상에서 제외 (READY 후보만 가능) */
    EXCLUDED
}
