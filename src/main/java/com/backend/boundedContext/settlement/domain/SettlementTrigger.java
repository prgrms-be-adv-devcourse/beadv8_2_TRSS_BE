package com.backend.boundedContext.settlement.domain;

/**
 * 처리를 시작한 경로(settlement_process_history.trigger_type)
 * 지급할 수 있는 상태는 실행 주체가 아니라 자동 지급인지 관리자 재처리인지로 정한다(Settlement.isPayable 참고)
 * 그래서 관리자가 수동 실행한 월 정산 Job은 ADMIN으로 이력에 남지만 자동 지급과 같은 기준으로 지급한다
 */
public enum SettlementTrigger {
    /** 구매 확정 이벤트 */
    EVENT,
    /** 매월 10일 월 정산 Job */
    MONTHLY_JOB,
    /** 재시도 스케줄러 */
    RETRY_SCHEDULER,
    /** 관리자 수동 실행·재처리·보류·제외 */
    ADMIN
}