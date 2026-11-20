package com.backend.boundedContext.settlement.out;

/**
 * 판매자별로 더한 집계 대상(READY, 정산월 ≤ 대상월) 정산 후보 금액 (관리자 정산 미리보기용)
 * carriedOverCount는 정산월이 대상월보다 이전인(이월될) 후보 수다
 * JPQL count()·sum()은 Long을 돌려주므로 필드도 Long
 */
public record SettlementSellerAmountRow(
        Long sellerId,
        Long itemCount,
        Long carriedOverCount,
        Long saleTotal,
        Long feeTotal,
        Long payoutTotal
) {
}