package com.backend.boundedContext.settlement.in.dto;

/**
 * 관리자 월 정산 화면 위 요약 카드 (조회 조건의 정산월·기간·판매자 기준, 상태 탭과 관계없이 전체 상태)
 *
 * @param totalCount          전체 정산서 수
 * @param paidCount           지급 완료 건수
 * @param paidAmount          지급 완료 지급액 합
 * @param scheduledCount      지급 예정(READY) 건수
 * @param actionRequiredCount 처리 필요 = retryingCount + manualRequiredCount
 * @param retryingCount       재시도 중(FAILED, 자동 처리 중)
 * @param manualRequiredCount 수동 처리 필요(MANUAL_REQUIRED)
 * @param onHoldCount         보류(HOLD)
 */
public record SettlementSummaryResponse(
        long totalCount,
        long paidCount,
        long paidAmount,
        long scheduledCount,
        long actionRequiredCount,
        long retryingCount,
        long manualRequiredCount,
        long onHoldCount
) {
}
