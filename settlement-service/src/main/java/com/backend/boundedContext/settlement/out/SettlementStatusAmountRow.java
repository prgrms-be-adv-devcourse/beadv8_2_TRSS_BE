package com.backend.boundedContext.settlement.out;

import com.backend.boundedContext.settlement.domain.SettlementStatus;

/**
 * 정산서 상태별 건수와 지급액 합 (관리자·판매자 정산 요약용)
 * JPQL count()·sum()은 Long을 돌려주므로 필드도 Long이고, 그룹이 있으면 값이 null이 될 일은 없다
 */
public record SettlementStatusAmountRow(
        SettlementStatus status,
        Long settlementCount,
        Long payoutTotal
) {
}
