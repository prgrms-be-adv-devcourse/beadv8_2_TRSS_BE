package com.backend.boundedContext.settlement.domain;

/** 처리 단계(settlement_process_history.stage) */
public enum SettlementHistoryStage {
    /** 구매 확정 이벤트 수신, 금액 검증, 수수료 계산, 저장 */
    CANDIDATE_CREATE,
    /** 원장 재대조: 지급된 정산서의 원장을 다시 읽어 지급 영수증과 비교(매일 03:35 스케줄러). 이미 쌓인 이력과 맞추려고 이름은 VERIFY 그대로 둔다 */
    VERIFY,
    /**
     * 출력 대사: 지급을 시도했지만 PAID가 아닌 정산서에 결제 원장의 지급 기록이 있는지 본다(매일 03:45 스케줄러).
     * 원장 재대조(VERIFY)와 단계를 나눠, 이 결과는 원장 불일치 보류(LedgerMismatchRule)에 쓰지 않는다
     */
    RECONCILE,
    /** 판별: 지급 직전 자격 판정(후보 금액 스냅샷, 판매자 상태, 원장 불일치 이력) */
    JUDGE,
    /** 확정: 정산 후보를 판매자별 정산서로 묶음 */
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
