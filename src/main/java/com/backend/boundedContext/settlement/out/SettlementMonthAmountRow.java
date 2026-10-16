package com.backend.boundedContext.settlement.out;

/**
 * 정산월별로 더한 READY 정산 후보 금액 (판매자 정산 예정 금액 조회용)
 * JPQL count()·sum()은 Long을 돌려주므로 필드도 Long이고, 그룹이 있으면 값이 null이 될 일은 없다
 */
public record SettlementMonthAmountRow(
        String settlementMonth,
        Long itemCount,
        Long saleTotal,
        Long feeTotal,
        Long payoutTotal
) {
}