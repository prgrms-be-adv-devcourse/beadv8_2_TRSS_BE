package com.backend.boundedContext.settlement.in.dto;

import java.util.List;

/** 판매자 정산 예정 금액: 전체 합계 + 정산월별 내역(오래된 달부터) */
public record SettlementExpectedResponse(
        long itemCount,
        long totalSalePrice,
        long totalFee,
        long payoutAmount,
        List<SettlementExpectedMonthResponse> months
) {
    public static SettlementExpectedResponse of(List<SettlementExpectedMonthResponse> months) {
        return new SettlementExpectedResponse(
                months.stream().mapToLong(SettlementExpectedMonthResponse::itemCount).sum(),
                months.stream().mapToLong(SettlementExpectedMonthResponse::totalSalePrice).sum(),
                months.stream().mapToLong(SettlementExpectedMonthResponse::totalFee).sum(),
                months.stream().mapToLong(SettlementExpectedMonthResponse::payoutAmount).sum(),
                months);
    }
}