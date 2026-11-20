package com.backend.boundedContext.settlement.domain;

/**
 * 정산서 보류 종류(settlement.hold_type)
 *
 * 보류를 푸는 경로가 종류마다 다르므로, 사유 문자열이 아니라 이 값으로 구분한다.
 *   ADMIN: 관리자가 정산서 1건을 보류. 관리자가 직접 해제한다
 *   SANCTION: 판매자 제재로 보류(제재 이벤트, 확정 단계 판별, 지급 직전 확인). 판매자 제재 해지 이벤트를 받으면 자동 해제한다
 *   LEDGER_MISMATCH: 원장 재대조 불일치로 보류. 불일치가 풀리거나 관리자가 불일치를 확인하면 자동 해제한다
 */
public enum SettlementHoldType {
    ADMIN,
    SANCTION,
    LEDGER_MISMATCH
}
