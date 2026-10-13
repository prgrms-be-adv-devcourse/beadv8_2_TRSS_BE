package com.backend.boundedContext.settlement.domain;

/**
 * 처리를 시작한 경로(settlement_process_history.trigger_type)
 * 지급에서는 경로마다 지급할 수 있는 상태가 다르다(Settlement.isPayableBy 참고)
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