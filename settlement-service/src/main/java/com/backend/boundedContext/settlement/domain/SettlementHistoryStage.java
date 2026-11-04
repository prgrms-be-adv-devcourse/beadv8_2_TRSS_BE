package com.backend.boundedContext.settlement.domain;

/** 처리 단계(settlement_process_history.stage) */
public enum SettlementHistoryStage {
    /** 구매 확정 이벤트 수신, 금액 검증, 수수료 계산, 저장 */
    CANDIDATE_CREATE,
    /** 정산 후보를 판매자별 정산서로 묶음 */
    AGGREGATE,
    /** 잠금, 홀딩 차감, 지갑 입금, 원장 기록, PAID */
    PAYOUT,
    /** 보류 */
    HOLD,
    /** 보류 해제 */
    RELEASE,
    /** 관리자 제외 */
    EXCLUDE
}
