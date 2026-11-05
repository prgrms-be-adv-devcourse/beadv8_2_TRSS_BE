package com.backend.boundedContext.settlement.out;

/**
 * 정산서에 묶인(INCLUDED) 후보 합계
 * 지급 전 "정산서 합계 = 후보 합계" 확인에 쓴다
 * 후보가 없으면 sum()이 null이므로 0으로 바꾼다
 *
 * 인텔리제이 쿼리 타입 계산 오류(coalesce(sum(c.salePrice), 0L)의 결과를 int로 계산. 0L의 L(Long 표시)을 인식하지 못함)로 인해 오류 표시됨
 * -> 0 처리를 쿼리 대신 record하는 방식으로 수정
 */
public record SettlementCandidateTotalsRow(Long saleTotal, Long feeTotal, Long payoutTotal, Long count) {
    public SettlementCandidateTotalsRow {
        if (saleTotal == null) saleTotal = 0L;
        if (feeTotal == null) feeTotal = 0L;
        if (payoutTotal == null) payoutTotal = 0L;
    }
}