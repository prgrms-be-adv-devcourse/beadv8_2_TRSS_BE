package com.backend.boundedContext.settlement.out;

/**
 * 조건에 맞는 정산 후보 전체 합계 (관리자 정산 미리보기의 맨 위 합계)
 * 후보가 없으면 sum()이 null이므로 0으로 바꾼다 (SettlementCandidateTotalsRow와 같은 이유로 쿼리 대신 record에서 처리)
 */
public record SettlementAmountTotalsRow(Long sellerCount, Long itemCount, Long saleTotal, Long feeTotal,
                                        Long payoutTotal) {
    public SettlementAmountTotalsRow {
        if (sellerCount == null) sellerCount = 0L;
        if (itemCount == null) itemCount = 0L;
        if (saleTotal == null) saleTotal = 0L;
        if (feeTotal == null) feeTotal = 0L;
        if (payoutTotal == null) payoutTotal = 0L;
    }
}