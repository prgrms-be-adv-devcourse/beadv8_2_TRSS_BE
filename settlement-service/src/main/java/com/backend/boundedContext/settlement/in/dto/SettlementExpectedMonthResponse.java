package com.backend.boundedContext.settlement.in.dto;

import java.time.LocalDate;

/**
 * 정산 예정 금액 1개월분: 구매 확정됐지만 아직 정산서에 묶이지 않은(READY) 후보를 정산월별로 더한 값
 * expectedPayDate는 다가올 월 정산 Job 기준 추정일이다(실패·보류는 반영하지 않음)
 */
public record SettlementExpectedMonthResponse(
        String settlementMonth,
        long itemCount,
        long totalSalePrice,
        long totalFee,
        long payoutAmount,
        LocalDate expectedPayDate
) {
}